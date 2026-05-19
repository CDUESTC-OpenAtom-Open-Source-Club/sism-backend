package com.sism.workflow.application;

import com.sism.shared.domain.notification.NotificationProvider;
import com.sism.shared.domain.user.UserProvider;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.definition.WorkflowPreviewQueryService;
import com.sism.workflow.application.query.WorkflowReadModelMapper;
import com.sism.workflow.application.query.WorkflowReadModelService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.query.repository.WorkflowQueryRepository;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.WorkflowTaskRepository;
import com.sism.workflow.interfaces.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;

/**
 * BusinessWorkflowApplicationService - 业务工作流应用服务
 * 负责业务工作流的核心业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessWorkflowApplicationService {
    private static final String SUBMIT_COMMENT_VARIABLE = "submitComment";

    private static final String PLAN_ENTITY_TYPE = "PLAN";
    private static final String PLAN_REPORT_ENTITY_TYPE = "PLAN_REPORT";
    private static final String LEGACY_PLAN_REPORT_ENTITY_TYPE = "PlanReport";

    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowQueryRepository workflowQueryRepository;
    private final WorkflowApplicationService workflowApplicationService;
    private final WorkflowReadModelService workflowReadModelService;
    private final WorkflowReadModelMapper workflowReadModelMapper;
    private final WorkflowPreviewQueryService workflowPreviewQueryService;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final ApproverResolver approverResolver;
    private final UserProvider userProvider;
    private final NotificationProvider notificationProvider;

    // ==================== 工作流启动 ====================

    /**
     * 启动工作流
     */
    @Transactional
    public WorkflowInstanceResponse startWorkflow(StartWorkflowRequest request, Long userId, Long orgId) {
        log.info("Starting workflow: {}, entityId: {}, userId: {}",
                request.getWorkflowCode(), request.getBusinessEntityId(), userId);

        // 1. 检查工作流定义是否存在且已激活
        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefByCode(request.getWorkflowCode());
        if (flowDef == null) {
            throw new IllegalArgumentException("Workflow definition not found or not active: " + request.getWorkflowCode());
        }

        if (!flowDef.getIsActive()) {
            throw new IllegalStateException("Workflow definition is not active: " + request.getWorkflowCode());
        }

        // 2. 检查是否已存在未完成的工作流实例
        String entityType = request.getBusinessEntityType() != null
                ? request.getBusinessEntityType()
                : flowDef.getEntityType();

        AuditInstance resumableInstance = findResumableWithdrawnInstance(
                request.getBusinessEntityId(),
                entityType,
                flowDef.getId());
        if (resumableInstance != null) {
            AuditInstance resumed = workflowApplicationService.resumeWithdrawnAuditInstance(resumableInstance);
            return workflowReadModelMapper.toInstanceResponse(resumed);
        }

        boolean hasActive = hasActiveInstance(request.getBusinessEntityId(), entityType);
        if (hasActive) {
            throw new IllegalStateException(
                    "An active workflow already exists for this entity: " + request.getBusinessEntityId());
        }

        // 3. 创建审批实例
        AuditInstance instance = new AuditInstance();
        instance.setFlowDefId(flowDef.getId());
        instance.setEntityType(entityType);
        instance.setEntityId(request.getBusinessEntityId());

        // 4. 启动实例
        String submitComment = resolveSubmitComment(request);
        AuditInstance started = submitComment == null
                ? workflowApplicationService.startAuditInstance(instance, userId, orgId)
                : workflowApplicationService.startAuditInstance(instance, userId, orgId, submitComment);

        return workflowReadModelMapper.toInstanceResponse(started);
    }

    /**
     * 通过定义ID启动工作流实例
     */
    @Transactional
    public WorkflowInstanceResponse startWorkflowInstance(
            String definitionId, StartInstanceRequest request, Long userId, Long orgId) {

        log.info("Starting workflow instance by definitionId: {}, entityId: {}, userId: {}",
                definitionId, request.getBusinessEntityId(), userId);

        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefById(parseRequiredLong(definitionId, "definitionId"));
        if (flowDef == null) {
            throw new IllegalArgumentException("Workflow definition not found: " + definitionId);
        }

        StartWorkflowRequest startRequest = new StartWorkflowRequest();
        startRequest.setWorkflowCode(flowDef.getFlowCode());
        startRequest.setBusinessEntityId(request.getBusinessEntityId());
        startRequest.setBusinessEntityType(flowDef.getEntityType());
        startRequest.setVariables(request.getVariables());

        return startWorkflow(startRequest, userId, orgId);
    }

    // ==================== 工作流查询 ====================

    /**
     * 查询工作流定义列表
     */
    public PageResult<WorkflowDefinitionResponse> listDefinitions(int pageNum, int pageSize) {
        return workflowReadModelService.listDefinitions(pageNum, pageSize);
    }

    /**
     * 查询工作流实例列表
     */
    public PageResult<WorkflowInstanceResponse> listInstances(
            String definitionId, int pageNum, int pageSize) {
        return workflowReadModelService.listInstances(definitionId, pageNum, pageSize);
    }

    /**
     * 获取工作流实例详情
     */
    public WorkflowInstanceDetailResponse getInstanceDetail(String instanceId) {
        return workflowReadModelService.getInstanceDetail(instanceId);
    }

    public WorkflowInstanceDetailResponse getInstanceDetailByBusiness(String entityType, Long entityId) {
        return workflowReadModelService.getInstanceDetailByBusiness(entityType, entityId);
    }

    public List<WorkflowHistoryCardResponse> listInstanceHistoryByBusiness(String entityType, Long entityId) {
        return workflowReadModelService.listInstanceHistoryByBusiness(entityType, entityId);
    }

    public WorkflowDefinitionPreviewResponse getDefinitionPreview(String flowCode, Long requesterOrgId) {
        return workflowPreviewQueryService.getPreviewByCode(flowCode, requesterOrgId);
    }

    /**
     * 获取我的待办任务
     */
    public PageResult<WorkflowTaskResponse> getMyPendingTasks(Long userId, int pageNum) {
        return workflowReadModelService.getMyPendingTasks(userId, pageNum);
    }

    private boolean hasActiveInstance(Long entityId, String entityType) {
        if (isPlanReportEntityType(entityType)) {
            return auditInstanceRepository.hasActiveInstance(entityId, PLAN_REPORT_ENTITY_TYPE)
                    || auditInstanceRepository.hasActiveInstance(entityId, LEGACY_PLAN_REPORT_ENTITY_TYPE);
        }
        return auditInstanceRepository.hasActiveInstance(entityId, entityType);
    }

    private boolean isPlanReportEntityType(String entityType) {
        return PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType)
                || LEGACY_PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType);
    }

    private AuditInstance findResumableWithdrawnInstance(Long entityId, String entityType, Long flowDefId) {
        List<AuditInstance> candidates = isPlanReportEntityType(entityType)
                ? List.of(
                auditInstanceRepository.findByBusinessTypeAndBusinessId(PLAN_REPORT_ENTITY_TYPE, entityId),
                auditInstanceRepository.findByBusinessTypeAndBusinessId(LEGACY_PLAN_REPORT_ENTITY_TYPE, entityId))
                .stream()
                .flatMap(List::stream)
                .toList()
                : auditInstanceRepository.findByBusinessTypeAndBusinessId(entityType, entityId);

        return candidates.stream()
                .filter(instance ->
                        AuditInstance.STATUS_PENDING.equals(instance.getStatus())
                                || AuditInstance.STATUS_REJECTED.equals(instance.getStatus())
                                || AuditInstance.STATUS_WITHDRAWN.equals(instance.getStatus()))
                .filter(instance -> flowDefId == null || flowDefId.equals(instance.getFlowDefId()))
                .filter(instance -> instance.getStepInstances().stream()
                        .anyMatch(step -> AuditInstance.STEP_STATUS_WITHDRAWN.equals(step.getStatus())))
                .max(Comparator
                        .comparing(AuditInstance::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AuditInstance::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    // ==================== 工作流操作 ====================

    /**
     * 审批任务
     */
    @Transactional
    public WorkflowInstanceResponse approveTask(
            String taskId, ApprovalRequest request, Long userId) {

        log.info("Approving task: {}, userId: {}", taskId, userId);

        AuditInstance instance = resolveAuditInstanceForTask(taskId);

        AuditStepInstance currentStep = instance.resolveCurrentPendingStep()
                .orElseThrow(() -> new SecurityException("You are not authorized to approve this task"));
        AuditStepDef currentStepDef = resolveStepDefinition(instance, currentStep);
        WorkflowInstanceDetailResponse detailBeforeApproval =
                workflowReadModelService.getInstanceDetail(String.valueOf(instance.getId()));

        if (!approverResolver.canUserApprove(currentStepDef, userId, instance.getRequesterOrgId(), instance)) {
            throw new SecurityException("You are not authorized to approve this task");
        }

        String resolvedComment = resolveApprovalComment(request == null ? null : request.getComment());
        AuditInstance approved = workflowApplicationService.approveAuditInstance(
                instance, userId, resolvedComment);
        createApprovalResultNotification(
                approved,
                userId,
                detailBeforeApproval,
                currentStepDef,
                true,
                resolvedComment
        );

        return workflowReadModelMapper.toInstanceResponse(approved);
    }

    @Transactional
    public WorkflowInstanceResponse decideTask(
            String taskId, WorkflowTaskDecisionRequest request, Long userId) {
        if (Boolean.TRUE.equals(request.getApproved())) {
            ApprovalRequest approvalRequest = new ApprovalRequest();
            approvalRequest.setComment(resolveApprovalComment(request.getComment()));
            return approveTask(taskId, approvalRequest, userId);
        }

        RejectionRequest rejectionRequest = new RejectionRequest();
        rejectionRequest.setReason(resolveRejectComment(request.getComment()));
        return rejectTask(taskId, rejectionRequest, userId);
    }

    /**
     * 拒绝任务
     */
    @Transactional
    public WorkflowInstanceResponse rejectTask(
            String taskId, RejectionRequest request, Long userId) {

        log.info("Rejecting task: {}, userId: {}", taskId, userId);

        AuditInstance instance = resolveAuditInstanceForTask(taskId);

        AuditStepInstance currentStep = instance.resolveCurrentPendingStep()
                .orElseThrow(() -> new SecurityException("You are not authorized to reject this task"));
        AuditStepDef currentStepDef = resolveStepDefinition(instance, currentStep);
        WorkflowInstanceDetailResponse detailBeforeRejection =
                workflowReadModelService.getInstanceDetail(String.valueOf(instance.getId()));

        if (!approverResolver.canUserApprove(currentStepDef, userId, instance.getRequesterOrgId(), instance)) {
            throw new SecurityException("You are not authorized to reject this task");
        }

        String resolvedReason = resolveRejectComment(request == null ? null : request.getReason());
        AuditInstance rejected = workflowApplicationService.rejectAuditInstance(
                instance, userId, resolvedReason);
        createApprovalResultNotification(
                rejected,
                userId,
                detailBeforeRejection,
                currentStepDef,
                false,
                resolvedReason
        );

        return workflowReadModelMapper.toInstanceResponse(rejected);
    }

    /**
     * 转办任务
     */
    @Transactional
    public WorkflowInstanceResponse reassignTask(
            String taskId, ReassignRequest request, Long userId) {

        log.info("Reassigning task: {}, fromUserId: {}, toUserId: {}",
                taskId, userId, request.getTargetUserId());

        AuditInstance targetInstance = resolveAuditInstanceForTask(taskId);
        AuditInstance instance = workflowApplicationService.transferAuditInstance(
                targetInstance.getId(), request.getTargetUserId());

        return workflowReadModelMapper.toInstanceResponse(instance);
    }

    private AuditInstance resolveAuditInstanceForTask(String taskId) {
        Long numericTaskId = parseRequiredLong(taskId, "taskId");
        return auditInstanceRepository.findByStepInstanceId(numericTaskId)
                .or(() -> auditInstanceRepository.findById(numericTaskId))
                .or(() -> workflowTaskRepository.findById(numericTaskId)
                        .flatMap(workflowTask -> {
                            String workflowId = workflowTask.getWorkflowId();
                            if (workflowId == null || workflowId.isBlank()) {
                                return Optional.empty();
                            }
                            try {
                                return auditInstanceRepository.findById(parseRequiredLong(workflowId, "workflowId"));
                            } catch (IllegalArgumentException ex) {
                                log.warn("Workflow task {} has non-numeric workflowId {}", numericTaskId, workflowId);
                                return Optional.empty();
                            }
                        }))
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private AuditStepDef resolveStepDefinition(AuditInstance instance, AuditStepInstance currentStep) {
        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefById(instance.getFlowDefId());
        if (flowDef == null || flowDef.getSteps() == null || flowDef.getSteps().isEmpty()) {
            throw new IllegalStateException("Workflow definition not found for instance: " + instance.getId());
        }

        return flowDef.getSteps().stream()
                .filter(step -> step.getId() != null && step.getId().equals(currentStep.getStepDefId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow step definition not found for step: " + currentStep.getStepDefId()));
    }

    /**
     * 取消工作流实例
     */
    @Transactional
    public void cancelInstance(String instanceId, Long userId) {
        log.info("Cancelling instance: {}, userId: {}", instanceId, userId);

        AuditInstance instance = auditInstanceRepository.findById(parseRequiredLong(instanceId, "instanceId"))
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));

        // 权限检查：只有发起人可以取消
        if (!instance.getRequesterId().equals(userId)) {
            throw new SecurityException("Only requester can cancel this workflow");
        }

        workflowApplicationService.cancelAuditInstance(instance);
    }

    // ==================== 流程定义管理 ====================

    /**
     * 根据ID获取工作流定义
     */
    public WorkflowDefinitionResponse getDefinitionById(String definitionId) {
        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefById(parseRequiredLong(definitionId, "definitionId"));
        if (flowDef == null) {
            throw new IllegalArgumentException("Workflow definition not found: " + definitionId);
        }
        return workflowReadModelMapper.toDefinitionResponse(flowDef);
    }

    /**
     * 根据代码获取工作流定义
     */
    public WorkflowDefinitionResponse getDefinitionByCode(String flowCode) {
        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefByCode(flowCode);
        if (flowDef == null) {
            throw new IllegalArgumentException("Workflow definition not found: " + flowCode);
        }
        return workflowReadModelMapper.toDefinitionResponse(flowDef);
    }

    /**
     * 根据实体类型获取工作流定义列表
     */
    public List<WorkflowDefinitionResponse> getDefinitionsByEntityType(String entityType) {
        return workflowDefinitionQueryService.getAuditFlowDefsByEntityType(entityType).stream()
                .map(workflowReadModelMapper::toDefinitionResponse)
                .toList();
    }

    /**
     * 创建工作流定义
     */
    @Transactional
    public WorkflowDefinitionResponse createDefinition(CreateWorkflowDefinitionRequest request) {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setFlowCode(request.getDefinitionCode());
        flowDef.setFlowName(request.getDefinitionName());
        flowDef.setEntityType(request.getCategory());
        flowDef.setDescription(request.getDescription());
        flowDef.setIsActive(request.isActive());
        flowDef.setVersion(request.getVersion() != null ? request.getVersion() : 1);
        if (request.getSteps() != null) {
            request.getSteps().forEach(stepRequest -> {
                AuditStepDef step = new AuditStepDef();
                step.setStepName(stepRequest.getStepName());
                step.setStepOrder(stepRequest.getStepOrder());
                step.setStepType(stepRequest.getStepType());
                step.setRoleId(stepRequest.getRoleId());
                flowDef.addStep(step);
            });
        }

        AuditFlowDef created = workflowDefinitionQueryService.createAuditFlowDef(flowDef);
        return workflowReadModelMapper.toDefinitionResponse(created);
    }

    // ==================== 实例查询 ====================

    /**
     * 获取我已审批的实例列表
     */
    public PageResult<WorkflowInstanceResponse> getMyApprovedInstances(Long userId, int pageNum, int pageSize) {
        return workflowReadModelService.getMyApprovedInstances(userId, pageNum, pageSize);
    }

    /**
     * 获取我发起的实例列表
     */
    public PageResult<WorkflowInstanceResponse> getMyAppliedInstances(Long userId, int pageNum, int pageSize) {
        return workflowReadModelService.getMyAppliedInstances(userId, pageNum, pageSize);
    }

    // ==================== 统计 ====================

    /**
     * 获取工作流统计信息
     */
    public Map<String, Object> getStatistics() {
        return workflowApplicationService.getApprovalStatistics();
    }

    // ==================== 私有方法 ====================

    private void createApprovalResultNotification(
            AuditInstance instance,
            Long userId,
            WorkflowInstanceDetailResponse detailSnapshot,
            AuditStepDef currentStepDef,
            boolean approved,
            String comment
    ) {
        if (approved && !AuditInstance.STATUS_APPROVED.equalsIgnoreCase(instance.getStatus())) {
            return;
        }

        Long senderOrgId = userProvider.getUserOrgId(userId).orElse(null);
        String businessName = firstNonBlank(
                detailSnapshot == null ? null : detailSnapshot.getPlanName(),
                instance.getEntityId() == null ? null : "业务对象#" + instance.getEntityId()
        );
        String stepName = firstNonBlank(
                currentStepDef == null ? null : currentStepDef.getStepName(),
                detailSnapshot == null ? null : detailSnapshot.getCurrentStepName(),
                "当前审批环节"
        );

        notificationProvider.createApprovalResultNotification(
                instance.getRequesterId(),
                userId,
                senderOrgId,
                instance.getId(),
                instance.getEntityType(),
                instance.getEntityId(),
                businessName,
                stepName,
                approved,
                comment
        );
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private long parseRequiredLong(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be a numeric value");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + " must be a numeric value: " + value, ex);
        }
    }

    private String resolveSubmitComment(StartWorkflowRequest request) {
        if (request == null || request.getVariables() == null) {
            return null;
        }
        Object rawComment = request.getVariables().get(SUBMIT_COMMENT_VARIABLE);
        if (!(rawComment instanceof String comment)) {
            return null;
        }
        String normalized = comment.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String resolveApprovalComment(String comment) {
        if (comment != null && !comment.trim().isEmpty()) {
            return comment.trim();
        }
        return "审批通过";
    }

    private String resolveRejectComment(String comment) {
        if (comment != null && !comment.trim().isEmpty()) {
            return comment.trim();
        }
        return "审批驳回";
    }
}
