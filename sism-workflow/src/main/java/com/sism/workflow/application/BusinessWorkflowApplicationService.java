package com.sism.workflow.application;

import com.sism.shared.domain.integration.DingTalkTodoProvider;
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
import java.util.Set;
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
    /**
     * 2026-10-29 定案修正（与 execution 侧 IndicatorAppraisalApplicationService
     * 的 APPRAISAL_ALLOWED_STEP_ORDERS 保持同步）：审核进度可评定的节点序号——
     * 职能部门链=步骤 4；学院链=步骤 3、5。其余节点只审核不评级，
     * 鉴定完整性校验仅在可评级节点通过前执行，避免「不能通过、也不能评级」的死锁。
     */
    private static final Map<String, Set<Integer>> APPRAISAL_REQUIRED_STEP_ORDERS = Map.of(
            "PLAN_APPROVAL_FUNCDEPT", Set.of(4),
            "PLAN_APPROVAL_COLLEGE", Set.of(3, 5)
    );

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
    private final DingTalkTodoProvider dingTalkTodoProvider;
    private final java.util.List<com.sism.shared.domain.workflow.WorkflowBusinessContextPort> workflowBusinessContextPorts;

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
            // 2026-10-31 修复（重报说明被丢弃，图05）：复活撤回实例时带上本次提交说明，
            // 写入提交步备注（PLAN 链事件变量已携带 submitComment，此前在此处被丢弃）。
            AuditInstance resumed = workflowApplicationService.resumeWithdrawnAuditInstance(
                    resumableInstance, resolveSubmitComment(request));
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
        return approveTask(taskId, request, userId, null);
    }

    /**
     * 审批任务（可携带本节点审核进度，P1 上报链改造）
     */
    /**
     * 2026-10-19 用户定案：自动审批（导入一键下发）与人工审批走同一套通知。
     * 每自动通过一个节点，都按普通审批的口径通知：
     * a) 提交人收到「流程已推进」；
     * b) 下一环节全部候选审批人收到「待审批」；
     * c) 终态时通知提交人结果。
     *
     * 与 approveTask 的区别：跳过权限校验与审核进度投影（系统管理员模拟人工处理）。
     * 返回 null 表示该实例已不在待审状态（无需再处理）。
     */
    @Transactional
    public AuditInstance autoApproveCurrentStep(AuditInstance instance, Long systemAdminUserId, String comment) {
        if (instance == null || !AuditInstance.STATUS_PENDING.equalsIgnoreCase(instance.getStatus())) {
            return null;
        }
        AuditStepInstance currentStep = instance.resolveCurrentPendingStep().orElse(null);
        if (currentStep == null) {
            return null;
        }
        AuditStepDef currentStepDef = resolveStepDefinition(instance, currentStep);
        WorkflowInstanceDetailResponse detailBeforeApproval =
                workflowReadModelService.getInstanceDetail(String.valueOf(instance.getId()));

        // 2026-10-20：走系统代批通道——自动审批以 admin 身份推进，
        // 节点被指派人可能是业务账号（如战略部负责人），不能按普通审批人校验拦截。
        AuditInstance approved = workflowApplicationService.approveAuditInstanceAsSystem(
                instance, systemAdminUserId, comment);

        createApprovalResultNotification(
                approved, systemAdminUserId, detailBeforeApproval, currentStepDef, true, comment);
        syncDingTalkTodosOnApproval(approved, currentStep, detailBeforeApproval);
        // 2026-10-20 用户定案（复盘 P1）：自动审批不调用 notifyNextStepOnApproval。
        // 该方法会给「下一环节候选人」发「请尽快审批」——但自动审批会在同一瞬间把
        // 下一节点也批掉，通知发出时已过期，只会造成"点进去没活了"的困惑。
        // 改为给被代批节点的候选人写「系统代批留痕」通知（落库可查证，不弹窗）。
        notifySystemAutoApprovedStep(
                approved, detailBeforeApproval, currentStep, currentStepDef, systemAdminUserId);
        markOwnSubmittedNotificationsRead(instance.getId(), systemAdminUserId);

        return approved;
    }

    /**
     * 2026-10-20 用户定案（复盘 P1）：导入自动下发时系统管理员代批了本该由
     * 战略部负责人 / 分管校领导处理的节点。给该节点候选人写留痕通知——
     * 消息中心可查证「谁在什么时候代批了哪个节点」，但不发实时弹窗。
     */
    private void notifySystemAutoApprovedStep(
            AuditInstance approved,
            WorkflowInstanceDetailResponse detailSnapshot,
            AuditStepInstance handledStep,
            AuditStepDef handledStepDef,
            Long systemAdminUserId) {
        if (handledStep == null || handledStepDef == null) {
            return;
        }
        if (handledStepDef.isSubmitStep()) {
            return;
        }
        try {
            Long senderOrgId = userProvider.getUserOrgId(systemAdminUserId).orElse(null);
            String businessName = firstNonBlank(
                    detailSnapshot == null ? null : detailSnapshot.getPlanName(),
                    approved.getEntityId() == null ? null : "业务对象#" + approved.getEntityId());
            String stepName = firstNonBlank(
                    handledStepDef.getStepName(), handledStep.getStepName(), "审批环节");
            String operatorName = approverResolver.resolveApproverName(systemAdminUserId);

            Long assignedApproverId = handledStep.getApproverId();
            if (assignedApproverId != null && !assignedApproverId.equals(systemAdminUserId)) {
                notificationProvider.createSystemAutoApprovedNotification(
                        assignedApproverId, systemAdminUserId, senderOrgId,
                        approved.getId(), approved.getEntityType(), approved.getEntityId(),
                        businessName, stepName, operatorName);
                return;
            }
            approverResolver.resolveCandidates(handledStepDef, approved.getRequesterOrgId(), approved)
                    .stream()
                    .map(candidate -> candidate.getUserId())
                    .filter(userId -> userId != null && !userId.equals(systemAdminUserId))
                    .distinct()
                    .forEach(userId -> {
                        try {
                            notificationProvider.createSystemAutoApprovedNotification(
                                    userId, systemAdminUserId, senderOrgId,
                                    approved.getId(), approved.getEntityType(), approved.getEntityId(),
                                    businessName, stepName, operatorName);
                        } catch (Exception ex) {
                            log.warn("Failed to write auto-approved notice for user {}: {}",
                                    userId, ex.getMessage());
                        }
                    });
        } catch (Exception ex) {
            log.warn("Failed to write auto-approved notice for instance {}: {}",
                    approved.getId(), ex.getMessage());
        }
    }

    @Transactional
    public WorkflowInstanceResponse approveTask(
            String taskId, ApprovalRequest request, Long userId, String appraisalLevel) {
        return approveTask(taskId, request, userId, appraisalLevel, null);
    }

    @Transactional
    public WorkflowInstanceResponse approveTask(
            String taskId, ApprovalRequest request, Long userId, String appraisalLevel,
            java.util.Map<Long, String> indicatorLevels) {

        log.info("Approving task: {}, userId: {}", taskId, userId);

        AuditInstance instance = resolveAuditInstanceForTask(taskId);

        AuditStepInstance currentStep = instance.resolveCurrentPendingStep()
                .orElseThrow(() -> new SecurityException("You are not authorized to approve this task"));
        AuditStepDef currentStepDef = resolveStepDefinition(instance, currentStep);
        WorkflowInstanceDetailResponse detailBeforeApproval =
                workflowReadModelService.getInstanceDetail(String.valueOf(instance.getId()));
        // 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案——
        // 记录本动作审批的环节号，供「整批动作是否完成」判定使用
        Integer approvedStepNo = currentStep.getStepNo();

        // N-03（2026-10-30）：审批范围组织统一由 ApproverResolver.resolveStepScopeOrgId 解析，
        // 这里不再用 instance.getRequesterOrgId() 直接决定「谁能审这一步」
        // （学院上报链的职能部门席位节点必须按职能部门本级判定，否则必然 403 死锁）。
        if (!approverResolver.canUserApprove(currentStepDef, userId, instance)) {
            throw new SecurityException("You are not authorized to approve this task");
        }

        String resolvedComment = resolveApprovalComment(request == null ? null : request.getComment());
        AuditInstance approved = workflowApplicationService.approveAuditInstance(
                instance, userId, resolvedComment, appraisalLevel);
        // B9 鉴定投影口径：仅当审批后实例到达终态（status == APPROVED，整条链走完）时，
        // 才把本次 decision 的审核进度投影到业务明细行；
        // 中间节点的鉴定仅写入 audit_step_instance.appraisal_level 留痕（approveAuditInstance 内既有逻辑）。
        // 2026-10-24 用户定案（指标级鉴定）：逐指标等级优先；整单等级仅作旧客户端兼容。
        boolean terminalApproved = AuditInstance.STATUS_APPROVED.equalsIgnoreCase(approved.getStatus());
        java.util.Map<Long, String> normalizedLevels = new java.util.LinkedHashMap<>();
        if (indicatorLevels != null) {
            indicatorLevels.forEach((indicatorId, level) -> {
                com.sism.enums.ProgressLevel normalized =
                        com.sism.enums.ProgressLevel.normalize(level);
                if (indicatorId != null && indicatorId > 0 && normalized != null) {
                    normalizedLevels.put(indicatorId, normalized.name());
                }
            });
        }

        // 2026-10-28 用户定案：上报链（PLAN_APPROVAL_*）每个节点通过前都校验——
        // 本报告实例下全部指标必须有审核进度；上一节点已评定的沿用，本级改动覆盖。
        // @Transactional 保证校验失败整单回滚。
        // 2026-10-29 定案修正（异动专项链路实测发现的死锁）：审核进度的评定权分布为
        // 「上级部门接手后的第一个节点 + 末节点」（FUNCDEPT=步骤4；COLLEGE=步骤3、5），
        // 其余节点（如学院链步骤 2 学院审核人）只审核、无评级权——若这些节点也要求
        // 「全部已评定」，审核人既不能通过、也无权评级，链路死锁。故校验收窄到
        // 「可评级的节点」通过前执行；末节点（最后一个可评级节点）通过前仍保证
        // 出系统时全部已评定。
        boolean reportApprovalChain =
                detailBeforeApproval != null
                && detailBeforeApproval.getFlowCode() != null
                && detailBeforeApproval.getFlowCode().toUpperCase().startsWith("PLAN_APPROVAL");
        if (reportApprovalChain) {
            boolean currentStepCanAppraise = currentStepDef != null
                    && currentStepDef.getStepOrder() != null
                    && APPRAISAL_REQUIRED_STEP_ORDERS
                            .getOrDefault(detailBeforeApproval.getFlowCode().toUpperCase(), Set.of())
                            .contains(currentStepDef.getStepOrder());
            if (currentStepCanAppraise) {
                java.util.Map<Long, String> effectiveLevels = new java.util.LinkedHashMap<>();
                workflowBusinessContextPorts.forEach(port ->
                        effectiveLevels.putAll(port.findIndicatorAppraisalLevels(instance.getEntityId())));
                effectiveLevels.putAll(normalizedLevels);
                // 2026-10-30 用户定案：拦截文案里的填报部门优先取报告的填报组织名，
                // 上下文不可得时兜底用审批卡的目标部门名（detailBeforeApproval 在本链路非空）。
                assertAllIndicatorsAppraised(instance, effectiveLevels,
                        detailBeforeApproval.getTargetOrgName());
            }
        }

        // 2026-10-28：逐指标等级在**每个节点**都投影——上级改了的覆盖下级，
        // 未改动的保留原值；不再等终态才落库。
        if (reportApprovalChain && !normalizedLevels.isEmpty()) {
            workflowBusinessContextPorts.forEach(port -> port.applyAppraisalLevelForIndicators(
                    instance.getEntityType(), instance.getEntityId(), normalizedLevels));
        } else if (terminalApproved && appraisalLevel != null && !appraisalLevel.isBlank()) {
            workflowBusinessContextPorts.forEach(port -> port.applyAppraisalLevel(
                    instance.getEntityType(), instance.getEntityId(), appraisalLevel));
        }
        endMutationIfTerminal(instance);
        // 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
        // 前端对整批异动是逐实例调用 approveTask；同一 batch 的每个实例各写一条
        // 结果/推进通知会让一次「审批动作」产生 N 条消息。按「批次内仍有同环节
        // 待审实例」判定整批动作未完成，未完成时跳过本实例的通知，由最后一个
        // 实例的动作统一产生 1 条结果/推进通知。仅作用于异动批次（batchId 非空），
        // PLAN 下发审批 / PLAN_REPORT 上报审批行为不变。
        boolean batchActionRoundIncomplete =
                isMutationBatchActionRoundIncomplete(instance, approvedStepNo);
        if (!batchActionRoundIncomplete) {
            createApprovalResultNotification(
                    approved,
                    userId,
                    detailBeforeApproval,
                    currentStepDef,
                    true,
                    resolvedComment
            );
        }
        syncDingTalkTodosOnApproval(approved, currentStep, detailBeforeApproval);
        // 2026-10-10 跨窗口实时修复：中间节点推进时通知提交人与下一环节审批人
        // （站内信 + WebSocket 推送），此前中间环节推进零通知，各窗口只能等心跳兜底。
        // 2026-10-30：整批异动动作未完成时同样跳过，避免一次动作产生 N 条推进通知。
        if (!batchActionRoundIncomplete) {
            notifyNextStepOnApproval(approved, detailBeforeApproval, currentStepDef, userId);
        }
        // 2026-09-27（main）：审批动作完成后将本人该实例的待审批通知标记已读留痕
        markOwnSubmittedNotificationsRead(instance.getId(), userId);

        return workflowReadModelMapper.toInstanceResponse(approved);
    }

    /**
     * 2026-10-28 用户定案：上报链每个节点通过前，本报告实例下全部指标的鉴定进度
     * 等级必须已评定（超前完成/正常/延期）。
     *
     * 口径是「有效等级」：上一节点已评定的沿用，本级本次提交的覆盖。
     * 因此下级已填、上级不改也能直接通过；下级漏填时，本级会被拦下补齐。
     *
     * 2026-10-30 用户定案（文案）：拦截消息不报指标 ID（报名称）、不出现「资源冲突」、
     * 要讲清楚去哪里做什么（见 {@link #buildUnappraisedAuditMessage}）。
     *
     * @param instance        本次审批的实例（entityType=PLAN_REPORT）
     * @param effectiveLevels 已评定的有效等级（历史 + 本次提交）
     * @param fallbackOrgName 端上取不到填报组织名时的兜底（如审批卡的目标部门名）
     */
    private void assertAllIndicatorsAppraised(AuditInstance instance,
                                              java.util.Map<Long, String> effectiveLevels,
                                              String fallbackOrgName) {
        java.util.List<Long> requiredIndicatorIds = workflowBusinessContextPorts.stream()
                .map(port -> port.findIndicatorIdsByReport(instance.getEntityId()))
                .filter(java.util.Objects::nonNull)
                .flatMap(java.util.List::stream)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (requiredIndicatorIds.isEmpty()) {
            return;
        }

        java.util.List<Long> unappraised = requiredIndicatorIds.stream()
                .filter(indicatorId -> {
                    String level = effectiveLevels.get(indicatorId);
                    return level == null || level.isBlank();
                })
                .toList();
        if (!unappraised.isEmpty()) {
            java.util.Map<Long, String> indicatorNames = new java.util.LinkedHashMap<>();
            workflowBusinessContextPorts.forEach(port -> {
                java.util.Map<Long, String> names =
                        port.findIndicatorNamesByReport(instance.getEntityId());
                if (names != null) {
                    indicatorNames.putAll(names);
                }
            });
            String reportOrgName = workflowBusinessContextPorts.stream()
                    .map(port -> port.findReportOrgName(instance.getEntityId()))
                    .filter(java.util.Objects::nonNull)
                    .flatMap(java.util.Optional::stream)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(fallbackOrgName);
            throw new com.sism.exception.BusinessConflictException(
                    buildUnappraisedAuditMessage(unappraised, indicatorNames, reportOrgName));
        }
    }

    /**
     * 2026-10-30 用户定案的拦截文案（三处修正：不讲资源冲突、不报指标 ID、讲清去哪做什么）：
     * 「未完成审核：还有 2 项指标的审核进度未鉴定（『教学运行质量』『科研产出增长率』）。
     *   请到 XX学院 的指标填报页完成全部指标的审核进度鉴定，再回到审批中心通过。」
     * 取不到填报组织名时退化为「请到指标填报页完成…」；名称缺失兜底「未知指标」。
     *
     * 2026-10-30 门禁前移改造：指标点名/20 字截断/「等 N 项」/未知指标兜底的取数与拼装
     * 抽到 {@link com.sism.shared.domain.indicator.IndicatorGateMessages}，
     * 与提交侧自评门禁（sism-execution）共用同一套口径。
     */
    private String buildUnappraisedAuditMessage(java.util.List<Long> unappraised,
                                                java.util.Map<Long, String> indicatorNames,
                                                String reportOrgName) {
        String goWhere = reportOrgName == null || reportOrgName.isBlank()
                ? "请到指标填报页"
                : "请到 " + reportOrgName + " 的指标填报页";
        return com.sism.shared.domain.indicator.IndicatorGateMessages.buildNamedGateMessage(
                "未完成审核",
                "审核进度未鉴定",
                unappraised,
                indicatorNames,
                goWhere + "完成全部指标的审核进度鉴定，再回到审批中心通过。");
    }

    @Transactional
    public WorkflowInstanceResponse decideTask(
            String taskId, WorkflowTaskDecisionRequest request, Long userId) {
        if (Boolean.TRUE.equals(request.getApproved())) {
            // 审核进度（P1 上报链改造）：通过时可选填写，归一校验（旧预警档位码归并为 DELAYED）
            String appraisalLevel = com.sism.enums.ProgressLevel
                    .normalize(request.getAppraisalLevel()) == null
                    ? null
                    : com.sism.enums.ProgressLevel.normalize(request.getAppraisalLevel()).name();
            ApprovalRequest approvalRequest = new ApprovalRequest();
            approvalRequest.setComment(resolveApprovalComment(request.getComment()));
            // 2026-10-24 指标级鉴定：逐指标等级随通过提交，仅末级审批节点由上级部门评定
            return approveTask(taskId, approvalRequest, userId, appraisalLevel,
                    request.getIndicatorLevels());
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

        // N-03（2026-10-30）：与通过同一口径，范围组织由 ApproverResolver 统一解析
        if (!approverResolver.canUserApprove(currentStepDef, userId, instance)) {
            throw new SecurityException("You are not authorized to reject this task");
        }

        String resolvedReason = resolveRejectComment(request == null ? null : request.getReason());
        AuditInstance rejected = workflowApplicationService.rejectAuditInstance(
                instance, userId, resolvedReason);
        // 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案——
        // 整批驳回同样是前端逐实例调用 rejectTask，只在整批动作完成时产生 1 条结果通知
        Integer rejectedStepNo = currentStep.getStepNo();
        if (!isMutationBatchActionRoundIncomplete(rejected, rejectedStepNo)) {
            createApprovalResultNotification(
                    rejected,
                    userId,
                    detailBeforeRejection,
                    currentStepDef,
                    false,
                    resolvedReason
            );
        }
        dingTalkTodoProvider.completeInstanceTodos(rejected.getId());
        markOwnSubmittedNotificationsRead(rejected.getId(), userId);

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
        AuditStepInstance pendingStep = targetInstance.resolveCurrentPendingStep().orElse(null);
        WorkflowInstanceDetailResponse detailSnapshot =
                workflowReadModelService.getInstanceDetail(String.valueOf(targetInstance.getId()));
        AuditInstance instance = workflowApplicationService.transferAuditInstance(
                targetInstance.getId(), request.getTargetUserId());

        if (pendingStep != null) {
            dingTalkTodoProvider.completeStepTodos(instance.getId(), pendingStep.getId());
            pushDingTalkTodoToUser(instance, pendingStep, detailSnapshot, request.getTargetUserId());
        }

        endMutationIfTerminal(instance);
        return workflowReadModelMapper.toInstanceResponse(instance);
    }

    /**
     * 把前端传来的 taskId 解析成审批实例。
     *
     * 2026-10-22 修复（异动驳回打错实例）：
     * 原实现先按「步骤实例 ID」查（findByStepInstanceId），查不到才按「实例 ID」查。
     * 但前端审批中心传的一律是**实例 ID**（PlanApprovalDetailItem.instanceId），
     * 而 audit_step_instance.id 与 audit_instance.id 是两套自增序列，极易撞号——
     * 线上实例：驳回实例 25 时，恰好存在 id=25 的步骤（属于早已撤回的实例 10），
     * 于是驳回打到了实例 10，报「Cannot reject: workflow is not in review」，
     * 用户看到的就是「填了驳回原因、点了确认，却没驳回成功」。
     *
     * 现在的顺序：先按实例 ID 精确匹配，命中且仍可处理即返回；否则再按步骤实例 ID
     * 反查（真正的「按待办任务 ID 审批」场景），最后兜底 workflowTask。
     * 这样实例 ID 永远优先，撞号时不会误伤别的实例。
     */
    private AuditInstance resolveAuditInstanceForTask(String taskId) {
        Long numericTaskId = parseRequiredLong(taskId, "taskId");

        // 1) 实例 ID 优先：前端审批中心传的就是它。
        //    只认仍在审批中的实例，避免把已结束实例当成可操作对象；
        //    但也绝不能在「实例存在但已结束」时改去按步骤 ID 查——那正是撞号误伤的来源。
        Optional<AuditInstance> byInstanceId = auditInstanceRepository.findById(numericTaskId);
        Optional<AuditInstance> activeByInstanceId = byInstanceId
                .filter(instance -> AuditInstance.STATUS_PENDING.equals(instance.getStatus()));
        if (activeByInstanceId.isPresent()) {
            return activeByInstanceId.get();
        }

        // 2) 再按步骤实例 ID 反查（真正的「按待办任务 ID 审批」场景）
        Optional<AuditInstance> byStepInstanceId = auditInstanceRepository.findByStepInstanceId(numericTaskId)
                .filter(instance -> AuditInstance.STATUS_PENDING.equals(instance.getStatus()));
        if (byStepInstanceId.isPresent()) {
            return byStepInstanceId.get();
        }

        // 3) 都不是进行中的实例时，按原顺序兜底，让下游给出准确错误（如「不在审批中」），
        //    而不是静默跳到另一个实例上
        return byInstanceId
                .or(() -> auditInstanceRepository.findByStepInstanceId(numericTaskId))
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
        dingTalkTodoProvider.completeInstanceTodos(instance.getId());
    }

    /**
     * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：异动「取消异动」终态动作。
     * 仅当实例处于「退回待重报」（驳回后退回填报人：WITHDRAWN、completedAt 为空、
     * 存在退回提交步骤，状态校验在 AuditInstance.cancelReturned）且调用者为实例
     * 发起人（requester）时，允许把同 batch 的全部异动实例一次性置为终态
     * CANCELLED（completedAt=now），整链归入「审批历史」。
     * 作用域收窄到 PLAN_MUTATION_STRATEGY 异动链（INDICATOR/TASK），
     * 上报/下发等其他流程继续走 {@link #cancelInstance}。
     */
    @Transactional
    public List<AuditInstance> cancelReturnedMutationBatch(String instanceId, Long userId) {
        log.info("Cancelling returned mutation batch by instance: {}, userId: {}", instanceId, userId);

        AuditInstance instance = auditInstanceRepository.findById(parseRequiredLong(instanceId, "instanceId"))
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));

        assertMutationChainInstance(instance);

        // 权限检查：只有异动发起人可以取消
        if (!java.util.Objects.equals(instance.getRequesterId(), userId)) {
            throw new SecurityException("仅异动发起人可取消");
        }

        // 同 batch 全部实例一次终结；无批次号的孤例只终结自身
        String batchId = instance.getBatchId();
        List<AuditInstance> batchInstances = batchId == null || batchId.isBlank()
                ? List.of(instance)
                : auditInstanceRepository.findByBatchId(batchId);
        boolean containsSelf = batchInstances.stream()
                .anyMatch(item -> instance.getId() != null && instance.getId().equals(item.getId()));
        if (!containsSelf) {
            batchInstances = java.util.stream.Stream.concat(batchInstances.stream(), java.util.stream.Stream.of(instance))
                    .toList();
        }

        List<AuditInstance> cancelled = workflowApplicationService.cancelReturnedMutationBatch(batchInstances);
        cancelled.forEach(item -> dingTalkTodoProvider.completeInstanceTodos(item.getId()));
        return cancelled;
    }

    /** 取消异动仅作用于 PLAN_MUTATION_STRATEGY 异动链（指标异动 INDICATOR / 任务改名 TASK）。 */
    private void assertMutationChainInstance(AuditInstance instance) {
        assertMutationChainInstance(instance, "取消");
    }

    /** action 用于错误文案区分「取消 / 撤回」等异动链专属动作。 */
    private void assertMutationChainInstance(AuditInstance instance, String action) {
        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefById(instance.getFlowDefId());
        String flowCode = flowDef == null ? null : flowDef.getFlowCode();
        if (!"PLAN_MUTATION_STRATEGY".equalsIgnoreCase(String.valueOf(flowCode))
                || (!"INDICATOR".equalsIgnoreCase(instance.getEntityType())
                        && !"TASK".equalsIgnoreCase(instance.getEntityType()))) {
            throw new IllegalArgumentException("仅异动审批链支持" + action + "操作");
        }
    }

    /**
     * 2026-10-30 用户定案（异动撤回=退回填报人活链）：异动发起人在审批中的「撤回」
     * 退回填报人节点、链保持存活。仅当实例处于审批中（IN_REVIEW，状态校验在
     * AuditInstance.withdrawToRequester）且调用者为实例发起人（requester）时，
     * 允许把同 batch 的全部异动实例一次性退回填报人：实例 WITHDRAWN、
     * completedAt 保持 NULL、在审审批步与最新提交类步原地置 WITHDRAWN
     * 「提交人撤回」（2026-10-30 二次定案：与上报撤回 cancel 同构的原地翻转、
     * 零新增行，不再追加退回提交步骤行）。不进审批历史；之后可修改后重新发起
     * （resume 复用同链）或「取消异动」终结。
     * 作用域收窄到 PLAN_MUTATION_STRATEGY 异动链（INDICATOR/TASK），
     * 上报/下发等其他流程继续走 {@link #cancelInstance}。
     */
    @Transactional
    public List<AuditInstance> withdrawMutationBatchToRequester(String instanceId, Long userId) {
        log.info("Withdrawing mutation batch to requester by instance: {}, userId: {}", instanceId, userId);

        AuditInstance instance = auditInstanceRepository.findById(parseRequiredLong(instanceId, "instanceId"))
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));

        assertMutationChainInstance(instance, "撤回");

        // 权限检查：只有异动发起人可以撤回
        if (!java.util.Objects.equals(instance.getRequesterId(), userId)) {
            throw new SecurityException("仅异动发起人可撤回");
        }

        // 同 batch 全部实例一次退回；无批次号的孤例只退回自身
        String batchId = instance.getBatchId();
        List<AuditInstance> batchInstances = batchId == null || batchId.isBlank()
                ? List.of(instance)
                : auditInstanceRepository.findByBatchId(batchId);
        boolean containsSelf = batchInstances.stream()
                .anyMatch(item -> instance.getId() != null && instance.getId().equals(item.getId()));
        if (!containsSelf) {
            batchInstances = java.util.stream.Stream.concat(batchInstances.stream(), java.util.stream.Stream.of(instance))
                    .toList();
        }

        List<AuditInstance> withdrawn = workflowApplicationService.withdrawMutationBatchToRequester(batchInstances);
        // 退回填报人后原待审批人不再持有待办，清理钉钉待办（与取消异动同口径）
        withdrawn.forEach(item -> dingTalkTodoProvider.completeInstanceTodos(item.getId()));
        return withdrawn;
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

    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
     * 判断本次审批动作之后，同一异动批次内是否仍有同环节（stepNo）待审的兄弟实例：
     * 有则说明整批动作未完成（前端逐实例调用审批接口），本次跳过结果/推进通知，
     * 由最后一个实例的动作统一产生 1 条通知。无批次号的实例（PLAN 下发 /
     * PLAN_REPORT 上报等）一律返回 false，行为与改造前完全一致。
     */
    private boolean isMutationBatchActionRoundIncomplete(AuditInstance handledInstance, Integer handledStepNo) {
        if (handledInstance == null) {
            return false;
        }
        String batchId = handledInstance.getBatchId();
        if (batchId == null || batchId.isBlank() || handledStepNo == null) {
            return false;
        }
        try {
            return auditInstanceRepository.countPendingBatchSiblingsAtStep(
                    batchId, handledInstance.getId(), handledStepNo) > 0;
        } catch (Exception ex) {
            // 判定失败不阻断审批主流程，退回逐实例通知的旧行为
            log.warn("Failed to count pending mutation batch siblings for instance {}: {}",
                    handledInstance.getId(), ex.getMessage());
            return false;
        }
    }

    /**
     * 2026-10-10 跨窗口实时修复：中间节点审批通过、流程推进到下一环节时——
     * a) 通知提交人（APPROVAL_NEXT_STEP），提交人窗口秒级收敛；
     * b) 通知下一环节全部候选审批人（APPROVAL_SUBMITTED），审批人窗口秒级亮起"处理审批"。
     * 终态审批已有 createApprovalResultNotification，不在此重复。
     */
    private void notifyNextStepOnApproval(
            AuditInstance approved,
            WorkflowInstanceDetailResponse detailSnapshot,
            AuditStepDef approvedStepDef,
            Long approverUserId) {
        if (AuditInstance.STATUS_APPROVED.equalsIgnoreCase(approved.getStatus())) {
            return;
        }
        try {
            Long senderOrgId = userProvider.getUserOrgId(approverUserId).orElse(null);
            String businessName = firstNonBlank(
                    detailSnapshot == null ? null : detailSnapshot.getPlanName(),
                    approved.getEntityId() == null ? null : "业务对象#" + approved.getEntityId()
            );
            String approverName = approverResolver.resolveApproverName(approverUserId);
            String nextStepName = approved.resolveCurrentPendingStep()
                    .map(nextStep -> resolveStepDefinition(approved, nextStep).getStepName())
                    .orElse("");

            notificationProvider.createStepAdvancedNotification(
                    approved.getRequesterId(),
                    approverUserId,
                    senderOrgId,
                    approved.getId(),
                    approved.getEntityType(),
                    approved.getEntityId(),
                    businessName,
                    nextStepName,
                    approverName
            );

            approved.resolveCurrentPendingStep().ifPresent(nextStep -> {
                AuditStepDef nextStepDef = resolveStepDefinition(approved, nextStep);
                approverResolver.resolveCandidates(nextStepDef, approved.getRequesterOrgId(), approved)
                        .forEach(candidate -> notificationProvider.createFlowAdvancedNotification(
                                candidate.getUserId(),
                                approverUserId,
                                senderOrgId,
                                approved.getId(),
                                approved.getEntityType(),
                                approved.getEntityId(),
                                businessName,
                                nextStepDef.getStepName(),
                                approverName
                        ));
            });
        } catch (Exception ex) {
            log.warn("Failed to notify next step approvers for instance {}: {}",
                    approved.getId(), ex.getMessage());
        }
    }

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

    // ==================== 钉钉待办联动 ====================

    /**
     * 2026-09-27 消息留痕（用户拍板）：审批人通过/驳回后，把其在该实例上仍未读的
     * 「待审批」通知标记为已读——消息保留在消息中心（已处理页签可见），仅状态归位。
     * fail-safe：留痕失败不影响审批主流程。
     */
    private void markOwnSubmittedNotificationsRead(Long approvalInstanceId, Long approverUserId) {
        try {
            notificationProvider.markApprovalSubmittedNotificationsRead(approvalInstanceId, approverUserId);
        } catch (Exception ex) {
            log.warn("Failed to mark submitted notifications read for instance {} approver {}: {}",
                    approvalInstanceId, approverUserId, ex.getMessage());
        }
    }

    /**
     * 审批通过后的钉钉待办联动：终态时清空整个实例的待办，
     * 中间环节推进时先完成当前环节待办，再给下一环节审批人推送新待办。
     */
    private void syncDingTalkTodosOnApproval(
            AuditInstance approved,
            AuditStepInstance approvedStep,
            WorkflowInstanceDetailResponse detailSnapshot
    ) {
        try {
            dingTalkTodoProvider.completeStepTodos(approved.getId(), approvedStep.getId());
            if (AuditInstance.STATUS_APPROVED.equalsIgnoreCase(approved.getStatus())) {
                dingTalkTodoProvider.completeInstanceTodos(approved.getId());
                return;
            }
            approved.resolveCurrentPendingStep().ifPresent(nextStep ->
                    pushDingTalkTodos(approved, nextStep, detailSnapshot));
        } catch (Exception ex) {
            log.warn("Failed to sync DingTalk todos for instance {}: {}",
                    approved.getId(), ex.getMessage());
        }
    }

    /**
     * 给指定环节的全部候选审批人推送钉钉待办（填补既有系统在中间环节推进时不通知下一审批人的缺口）。
     */
    private void pushDingTalkTodos(
            AuditInstance instance,
            AuditStepInstance stepInstance,
            WorkflowInstanceDetailResponse detailSnapshot
    ) {
        try {
            AuditStepDef stepDef = resolveStepDefinition(instance, stepInstance);
            approverResolver.resolveCandidates(stepDef, instance.getRequesterOrgId(), instance)
                    .forEach(candidate -> pushDingTalkTodoToUser(
                            instance, stepInstance, detailSnapshot, candidate.getUserId()));
        } catch (Exception ex) {
            log.warn("Failed to push DingTalk todos for instance {} step {}: {}",
                    instance.getId(), stepInstance.getId(), ex.getMessage());
        }
    }

    private void pushDingTalkTodoToUser(
            AuditInstance instance,
            AuditStepInstance stepInstance,
            WorkflowInstanceDetailResponse detailSnapshot,
            Long sysUserId
    ) {
        try {
            AuditStepDef stepDef = resolveStepDefinition(instance, stepInstance);
            String businessName = firstNonBlank(
                    detailSnapshot == null ? null : detailSnapshot.getPlanName(),
                    instance.getEntityId() == null ? null : "业务对象#" + instance.getEntityId()
            );
            dingTalkTodoProvider.pushApprovalTodo(new DingTalkTodoProvider.ApprovalTodoPush(
                    sysUserId,
                    instance.getId(),
                    stepInstance.getId(),
                    instance.getEntityType(),
                    instance.getEntityId(),
                    businessName,
                    stepDef == null ? null : stepDef.getStepName(),
                    approverResolver.resolveApproverName(instance.getRequesterId()),
                    detailSnapshot == null ? null : detailSnapshot.getSourceOrgName()
            ));
        } catch (Exception ex) {
            log.warn("Failed to push DingTalk todo to user {} for instance {}: {}",
                    sysUserId, instance.getId(), ex.getMessage());
        }
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

    private void endMutationIfTerminal(AuditInstance instance) {
        if (instance == null) {
            return;
        }
        // 2026-10-21：任务改名异动（TASK）与指标异动（INDICATOR）共用终态解锁口径
        String entityType = instance.getEntityType();
        if (!"INDICATOR".equalsIgnoreCase(entityType) && !"TASK".equalsIgnoreCase(entityType)) {
            return;
        }
        boolean terminal = !"PENDING".equalsIgnoreCase(instance.getStatus());
        if (!terminal) {
            return;
        }
        boolean approved = "APPROVED".equalsIgnoreCase(instance.getStatus());
        workflowBusinessContextPorts.forEach(port -> port.endIndicatorMutation(
                instance.getEntityType(), instance.getEntityId(), approved));
    }
}