package com.sism.execution.infrastructure.event;

import com.sism.execution.application.ReportApplicationService;
import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.ReportOrgType;
import com.sism.execution.domain.report.event.PlanReportApprovedEvent;
import com.sism.execution.domain.report.event.PlanReportRejectedEvent;
import com.sism.execution.domain.report.event.PlanReportSubmittedEvent;
import com.sism.iam.application.service.UserNotificationService;
import com.sism.shared.domain.integration.DingTalkTodoProvider;
import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.application.WorkflowApplicationService;
import com.sism.workflow.application.WorkflowTerminalStatusSyncService;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.query.WorkflowReadModelService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.interfaces.dto.WorkflowInstanceDetailResponse;
import com.sism.workflow.interfaces.dto.StartWorkflowRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Comparator;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReportWorkflowEventListener {

    private static final String PLAN_REPORT_ENTITY_TYPE = "PLAN_REPORT";
    private static final String REPORT_STATUS_APPROVED = "APPROVED";
    private static final String REPORT_STATUS_REJECTED = "REJECTED";
    private static final int MAX_START_ATTEMPTS = 3;

    private final BusinessWorkflowApplicationService businessWorkflowService;
    private final WorkflowApplicationService workflowApplicationService;
    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowTerminalStatusSyncService workflowTerminalStatusSyncService;
    private final ReportApplicationService reportApplicationService;
    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final WorkflowReadModelService workflowReadModelService;
    private final ApproverResolver approverResolver;
    private final UserNotificationService userNotificationService;
    private final DingTalkTodoProvider dingTalkTodoProvider;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePlanReportSubmitted(PlanReportSubmittedEvent event) {
        if (event == null) {
            log.warn("Received null PlanReportSubmittedEvent");
            return;
        }

        try {
            log.info("=== [工作流事件监听器] 接收到报告提交事件 ===");
            log.info("报告ID: {}, 报告月份: {}, 报告组织ID: {}",
                    event.getReportId(), event.getReportMonth(), event.getReportOrgId());

            PlanReport report = reportApplicationService.findReportById(event.getReportId()).orElse(null);
            ReportOrgType reportOrgType = report == null ? null : report.getReportOrgType();
            if (reportOrgType == null) {
                throw new IllegalArgumentException("Report not found: " + event.getReportId());
            }

            Long auditInstanceId = report == null ? null : report.getAuditInstanceId();
            AuditInstance resumableInstance = findResumableReportInstance(event.getReportId(), auditInstanceId);
            if (resumableInstance != null) {
                AuditInstance resumed = workflowApplicationService.resumeWithdrawnAuditInstance(resumableInstance);
                reportApplicationService.attachAuditInstance(event.getReportId(), resumed.getId());
                log.info("✅ 已恢复既有报告审批实例 - instanceId: {}, reportId: {}", resumed.getId(), event.getReportId());
                return;
            }

            StartWorkflowRequest request = new StartWorkflowRequest();
            request.setWorkflowCode(resolveWorkflowCode(reportOrgType));
            request.setBusinessEntityId(event.getReportId());
            request.setBusinessEntityType(PLAN_REPORT_ENTITY_TYPE);
            request.setVariables(buildWorkflowVariables(event));

            Long initiatorId = resolveSubmitterId(event);
            var response = startWorkflowWithRetry(request, initiatorId, event.getReportOrgId());
            log.info("✅ 工作流启动成功 - 工作流实例ID: {}, 报告ID: {}", response.getInstanceId(), event.getReportId());
            if (response.getInstanceId() != null) {
                Long instanceId = Long.parseLong(response.getInstanceId());
                reportApplicationService.attachAuditInstance(event.getReportId(), instanceId);
                notifyNextApprovers(event, request.getWorkflowCode(), instanceId, report);
            }
        } catch (IllegalStateException e) {
            log.warn("⚠️ 无法启动工作流（可能是因为已存在活跃实例）: reportId={}, reason={}", event.getReportId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ 启动工作流失败: reportId={}, 错误信息={}", event.getReportId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to start report workflow for reportId=" + event.getReportId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePlanReportApproved(PlanReportApprovedEvent event) {
        if (event == null) {
            log.warn("Received null PlanReportApprovedEvent");
            return;
        }
        try {
            log.info("=== [工作流事件监听器] 接收到报告批准事件 ===");
            log.info("报告ID: {}, 报告月份: {}, 报告组织ID: {}, 审批人: {}", event.getReportId(), event.getReportMonth(), event.getReportOrgId(), event.getApproverId());
            if (!hasReportStatus(event.getReportId(), REPORT_STATUS_APPROVED)) {
                throw new IllegalStateException("Report is not approved yet: " + event.getReportId());
            }
            int syncedCount = workflowTerminalStatusSyncService.syncReportWorkflowTerminalStatus(
                    event.getReportId(), AuditInstance.STATUS_APPROVED, event.getApproverId(), "Plan report approved");
            log.info("✅ 报告批准后置处理完成，同步工作流实例数量: {}", syncedCount);
        } catch (Exception e) {
            log.error("❌ 处理报告批准事件失败: reportId={}, 错误信息={}", event.getReportId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to process approved report event for reportId=" + event.getReportId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePlanReportRejected(PlanReportRejectedEvent event) {
        if (event == null) {
            log.warn("Received null PlanReportRejectedEvent");
            return;
        }
        try {
            log.info("=== [工作流事件监听器] 接收到报告驳回事件 ===");
            log.info("报告ID: {}, 报告月份: {}, 报告组织ID: {}, 驳回人: {}, 驳回原因: {}",
                    event.getReportId(), event.getReportMonth(), event.getReportOrgId(), event.getApproverId(), event.getReason());
            if (!hasReportStatus(event.getReportId(), REPORT_STATUS_REJECTED)) {
                throw new IllegalStateException("Report is not rejected yet: " + event.getReportId());
            }
            int syncedCount = workflowTerminalStatusSyncService.syncReportWorkflowTerminalStatus(
                    event.getReportId(), AuditInstance.STATUS_REJECTED, event.getApproverId(), event.getReason());
            log.info("✅ 报告驳回后置处理完成，同步工作流实例数量: {}", syncedCount);
        } catch (Exception e) {
            log.error("❌ 处理报告驳回事件失败: reportId={}, 错误信息={}", event.getReportId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to process rejected report event for reportId=" + event.getReportId(), e);
        }
    }

    private java.util.Map<String, Object> buildWorkflowVariables(PlanReportSubmittedEvent event) {
        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("reportId", event.getReportId());
        variables.put("reportMonth", event.getReportMonth());
        variables.put("reportOrgId", event.getReportOrgId());
        variables.put("submitterId", event.getSubmitterId());
        return variables;
    }

    private Long resolveSubmitterId(PlanReportSubmittedEvent event) {
        if (event.getSubmitterId() == null) {
            throw new IllegalArgumentException("PlanReportSubmittedEvent missing submitterId: " + event.getReportId());
        }
        return event.getSubmitterId();
    }

    private boolean hasReportStatus(Long reportId, String expectedStatus) {
        PlanReport report = reportApplicationService.findReportById(reportId).orElse(null);
        return report != null && expectedStatus.equalsIgnoreCase(report.getStatus());
    }

    private String resolveWorkflowCode(ReportOrgType reportOrgType) {
        return reportOrgType == ReportOrgType.COLLEGE ? "PLAN_APPROVAL_COLLEGE" : "PLAN_APPROVAL_FUNCDEPT";
    }

    private AuditInstance findResumableReportInstance(Long reportId, Long boundAuditInstanceId) {
        if (boundAuditInstanceId != null && boundAuditInstanceId > 0) {
            AuditInstance boundInstance = auditInstanceRepository.findById(boundAuditInstanceId).orElse(null);
            if (isResumableReportInstance(boundInstance)) {
                return boundInstance;
            }
        }
        List<AuditInstance> candidates = java.util.stream.Stream.concat(
                        auditInstanceRepository.findByBusinessTypeAndBusinessId(PLAN_REPORT_ENTITY_TYPE, reportId).stream(),
                        auditInstanceRepository.findByBusinessTypeAndBusinessId("PlanReport", reportId).stream())
                .distinct()
                .toList();
        return candidates.stream()
                .filter(this::isResumableReportInstance)
                .max(Comparator.comparing(AuditInstance::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AuditInstance::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private boolean isResumableReportInstance(AuditInstance instance) {
        if (instance == null) {
            return false;
        }
        String status = String.valueOf(instance.getStatus()).trim().toUpperCase();
        if (!AuditInstance.STATUS_PENDING.equals(status)
                && !AuditInstance.STATUS_REJECTED.equals(status)
                && !AuditInstance.STATUS_WITHDRAWN.equals(status)) {
            return false;
        }
        return instance.getStepInstances().stream()
                .anyMatch(step -> AuditInstance.STEP_STATUS_WITHDRAWN.equals(step.getStatus()));
    }

    private com.sism.workflow.interfaces.dto.WorkflowInstanceResponse startWorkflowWithRetry(
            StartWorkflowRequest request,
            Long submitterId,
            Long submitterOrgId
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
            try {
                return businessWorkflowService.startWorkflow(request, submitterId, submitterOrgId);
            } catch (IllegalStateException e) {
                throw e;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt == MAX_START_ATTEMPTS) {
                    break;
                }
                log.warn("Retrying report workflow start, attempt={}/{}, entityId={}, reason={}",
                        attempt + 1, MAX_START_ATTEMPTS, request.getBusinessEntityId(), e.getMessage());
            }
        }
        throw lastFailure == null ? new IllegalStateException("Unknown report workflow start failure") : lastFailure;
    }

    private void notifyNextApprovers(
            PlanReportSubmittedEvent event,
            String workflowCode,
            Long instanceId,
            PlanReport report
    ) {
        try {
            AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefByCode(workflowCode);
            if (flowDef == null || flowDef.getSteps() == null || flowDef.getSteps().isEmpty()) {
                return;
            }

            WorkflowInstanceDetailResponse detail = workflowReadModelService.getInstanceDetail(String.valueOf(instanceId));
            AuditStepDef nextApprovalStep = resolveNextApprovalStep(flowDef, detail == null ? null : detail.getCurrentStepName());
            if (nextApprovalStep == null) {
                return;
            }

            String submitterName = approverResolver.resolveApproverName(event.getSubmitterId());
            String businessName = resolveReportBusinessName(report, event);

            Long stepInstanceId = parseStepInstanceId(detail == null ? null : detail.getCurrentTaskId());
            approverResolver.resolveCandidates(nextApprovalStep, event.getReportOrgId()).forEach(candidate -> {
                userNotificationService.createSubmissionNotification(
                        candidate.getUserId(),
                        event.getSubmitterId(),
                        event.getReportOrgId(),
                        instanceId,
                        PLAN_REPORT_ENTITY_TYPE,
                        event.getReportId(),
                        businessName,
                        nextApprovalStep.getStepName(),
                        submitterName
                );
                dingTalkTodoProvider.pushApprovalTodo(new DingTalkTodoProvider.ApprovalTodoPush(
                        candidate.getUserId(),
                        instanceId,
                        stepInstanceId,
                        PLAN_REPORT_ENTITY_TYPE,
                        event.getReportId(),
                        businessName,
                        nextApprovalStep.getStepName(),
                        submitterName,
                        detail == null ? null : detail.getSourceOrgName()
                ));
            });
        } catch (Exception ex) {
            log.warn("Failed to notify report approvers for reportId={}, workflowCode={}: {}",
                    event.getReportId(), workflowCode, ex.getMessage(), ex);
        }
    }

    private Long parseStepInstanceId(String currentTaskId) {
        if (currentTaskId == null || currentTaskId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(currentTaskId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String resolveReportBusinessName(PlanReport report, PlanReportSubmittedEvent event) {
        if (report != null && report.getTitle() != null && !report.getTitle().isBlank()) {
            return report.getTitle().trim();
        }
        if (event.getReportMonth() != null && !event.getReportMonth().isBlank()) {
            return event.getReportMonth().trim() + "月报";
        }
        return "月报#" + event.getReportId();
    }

    private AuditStepDef resolveNextApprovalStep(AuditFlowDef flowDef, String currentStepName) {
        if (flowDef == null || flowDef.getSteps() == null) {
            return null;
        }

        if (currentStepName != null && !currentStepName.isBlank()) {
            String normalizedCurrentStepName = currentStepName.trim();
            AuditStepDef matched = flowDef.getSteps().stream()
                    .filter(AuditStepDef::isApprovalStep)
                    .filter(step -> normalizedCurrentStepName.equals(step.getStepName()))
                    .findFirst()
                    .orElse(null);
            if (matched != null) {
                return matched;
            }
        }

        return flowDef.getSteps().stream()
                .filter(AuditStepDef::isApprovalStep)
                .sorted(Comparator.comparing(step -> step.getStepOrder() == null ? Integer.MAX_VALUE : step.getStepOrder()))
                .findFirst()
                .orElse(null);
    }
}
