package com.sism.strategy.infrastructure.event;

import com.sism.iam.application.service.UserNotificationService;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.query.WorkflowReadModelService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.interfaces.dto.WorkflowInstanceDetailResponse;
import com.sism.strategy.domain.plan.event.PlanSubmittedForApprovalEvent;
import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.interfaces.dto.StartWorkflowRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

import java.util.Comparator;

@Component
@Slf4j
@RequiredArgsConstructor
public class PlanWorkflowEventListener {

    private static final String PLAN_ENTITY_TYPE = "PLAN";
    private static final int MAX_START_ATTEMPTS = 3;

    private final BusinessWorkflowApplicationService businessWorkflowApplicationService;
    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final WorkflowReadModelService workflowReadModelService;
    private final ApproverResolver approverResolver;
    private final UserNotificationService userNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePlanSubmittedForApproval(PlanSubmittedForApprovalEvent event) {
        if (event == null) {
            return;
        }

        try {
            StartWorkflowRequest request = new StartWorkflowRequest();
            request.setWorkflowCode(event.getWorkflowCode());
            request.setBusinessEntityId(event.getPlanId());
            request.setBusinessEntityType(PLAN_ENTITY_TYPE);
            request.setVariables(buildWorkflowVariables(event));

            var response = startWorkflowWithRetry(request, event.getSubmitterId(), event.getSubmitterOrgId());
            log.info("Started plan workflow for planId={}, instanceId={}", event.getPlanId(), response.getInstanceId());
            notifyNextApprovers(event, request.getWorkflowCode(), response);
        } catch (Exception ex) {
            log.error("Failed to start plan workflow for planId={}, workflowCode={}: {}",
                    event.getPlanId(), event.getWorkflowCode(), ex.getMessage(), ex);
            throw new IllegalStateException(
                    "Failed to start plan workflow for planId=" + event.getPlanId() + ", workflowCode=" + event.getWorkflowCode(),
                    ex
            );
        }
    }

    private Map<String, Object> buildWorkflowVariables(PlanSubmittedForApprovalEvent event) {
        Map<String, Object> variables = new HashMap<>();
        if (event.getComment() != null && !event.getComment().trim().isEmpty()) {
            variables.put("submitComment", event.getComment().trim());
        }
        return variables;
    }

    private com.sism.workflow.interfaces.dto.WorkflowInstanceResponse startWorkflowWithRetry(
            StartWorkflowRequest request,
            Long submitterId,
            Long submitterOrgId
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
            try {
                return businessWorkflowApplicationService.startWorkflow(request, submitterId, submitterOrgId);
            } catch (IllegalStateException e) {
                throw e;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt == MAX_START_ATTEMPTS) {
                    break;
                }
                log.warn("Retrying plan workflow start, attempt={}/{}, workflowCode={}, entityId={}, reason={}",
                        attempt + 1, MAX_START_ATTEMPTS, request.getWorkflowCode(), request.getBusinessEntityId(), e.getMessage());
            }
        }
        throw lastFailure == null ? new IllegalStateException("Unknown plan workflow start failure") : lastFailure;
    }

    private void notifyNextApprovers(
            PlanSubmittedForApprovalEvent event,
            String workflowCode,
            com.sism.workflow.interfaces.dto.WorkflowInstanceResponse response
    ) {
        try {
            Long instanceId = response.getInstanceId() == null ? null : Long.parseLong(response.getInstanceId());
            if (instanceId == null || instanceId <= 0) {
                return;
            }

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
            String businessName =
                    detail != null && detail.getPlanName() != null && !detail.getPlanName().isBlank()
                            ? detail.getPlanName().trim()
                            : "Plan " + event.getPlanId();

            approverResolver.resolveCandidates(nextApprovalStep, event.getSubmitterOrgId()).forEach(candidate ->
                    userNotificationService.createSubmissionNotification(
                            candidate.getUserId(),
                            event.getSubmitterId(),
                            event.getSubmitterOrgId(),
                            instanceId,
                            PLAN_ENTITY_TYPE,
                            event.getPlanId(),
                            businessName,
                            nextApprovalStep.getStepName(),
                            submitterName
                    )
            );
        } catch (Exception ex) {
            log.warn("Failed to notify plan approvers for planId={}, workflowCode={}: {}",
                    event.getPlanId(), workflowCode, ex.getMessage(), ex);
        }
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
