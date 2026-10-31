package com.sism.workflow.application.query;

import com.sism.shared.domain.user.UserIdentity;
import com.sism.shared.domain.user.UserProvider;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.query.repository.WorkflowQueryRepository;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.interfaces.dto.PageResult;
import com.sism.workflow.interfaces.dto.WorkflowHistoryCardResponse;
import com.sism.workflow.interfaces.dto.WorkflowHistoryResponse;
import com.sism.workflow.interfaces.dto.WorkflowInstanceDetailResponse;
import com.sism.workflow.interfaces.dto.WorkflowInstanceResponse;
import com.sism.workflow.interfaces.dto.WorkflowTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class WorkflowReadModelService {

    private static final String PLAN_ENTITY_TYPE = "PLAN";
    private static final String PLAN_REPORT_ENTITY_TYPE = "PLAN_REPORT";
    private static final String LEGACY_PLAN_REPORT_ENTITY_TYPE = "PlanReport";

    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowQueryRepository workflowQueryRepository;
    private final WorkflowReadModelMapper workflowReadModelMapper;
    private final UserProvider userProvider;
    private final java.util.List<WorkflowBusinessContextPort> workflowBusinessContextPorts;

    public PageResult<com.sism.workflow.interfaces.dto.WorkflowDefinitionResponse> listDefinitions(int pageNum, int pageSize) {
        int safePageNum = normalizePageNum(pageNum);
        int safePageSize = normalizePageSize(pageSize);
        Pageable pageable = PageRequest.of(safePageNum - 1, safePageSize);
        Page<AuditFlowDef> page = workflowDefinitionQueryService.getAllAuditFlowDefs(pageable);
        List<com.sism.workflow.interfaces.dto.WorkflowDefinitionResponse> items = page.getContent().stream()
                .map(workflowReadModelMapper::toDefinitionResponse)
                .toList();
        return PageResult.of(items, page.getTotalElements(), safePageNum, safePageSize);
    }

    public PageResult<WorkflowInstanceResponse> listInstances(String definitionId, int pageNum, int pageSize) {
        int safePageNum = normalizePageNum(pageNum);
        int safePageSize = normalizePageSize(pageSize);
        Pageable pageable = PageRequest.of(safePageNum - 1, safePageSize);
        Page<AuditInstance> page = auditInstanceRepository.findByFlowDefId(parseRequiredLong(definitionId, "definitionId"), pageable);
        Map<Long, String> userNameCache = new HashMap<>();
        Map<Long, String> orgNameCache = new HashMap<>();
        Map<Long, AuditFlowDef> flowDefCache = new HashMap<>();
        Map<String, WorkflowBusinessContext> businessContextCache = new HashMap<>();
        primeReadModelCaches(page.getContent(), userNameCache, orgNameCache, businessContextCache);
        List<WorkflowInstanceResponse> items = page.getContent().stream()
                .map(instance -> buildInstanceSummary(instance, userNameCache, orgNameCache, flowDefCache, businessContextCache))
                .toList();
        return PageResult.of(items, page.getTotalElements(), safePageNum, safePageSize);
    }

    public WorkflowInstanceDetailResponse getInstanceDetail(String instanceId) {
        AuditInstance instance = auditInstanceRepository.findById(parseRequiredLong(instanceId, "instanceId"))
                .orElseThrow(() -> new IllegalArgumentException("Workflow instance not found: " + instanceId));
        return buildInstanceDetail(instance);
    }

    public WorkflowInstanceDetailResponse getInstanceDetailByBusiness(String entityType, Long entityId) {
        return findLatestCurrentInstance(entityType, entityId)
                .map(this::buildInstanceDetail)
                .orElse(null);
    }

    public List<WorkflowHistoryCardResponse> listInstanceHistoryByBusiness(String entityType, Long entityId) {
        Map<Long, AuditFlowDef> flowDefCache = new HashMap<>();
        List<AuditInstance> qualified = findInstancesByBusiness(entityType, entityId).stream()
                .filter(instance -> isHistoryQualified(instance, flowDefCache))
                .sorted(Comparator
                        .comparing(AuditInstance::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AuditInstance::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Map<Long, String> userNameCache = new HashMap<>();
        Map<Long, String> orgNameCache = new HashMap<>();
        Map<String, WorkflowBusinessContext> businessContextCache = new HashMap<>();
        Map<Long, Integer> roundNoByInstanceId = new HashMap<>();
        for (int index = 0; index < qualified.size(); index++) {
            roundNoByInstanceId.put(qualified.get(index).getId(), index + 1);
        }

        return qualified.stream()
                .sorted(Comparator
                        .comparing(AuditInstance::getStartedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AuditInstance::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(instance -> buildHistoryCard(
                        instance,
                        roundNoByInstanceId.getOrDefault(instance.getId(), 0),
                        userNameCache,
                        orgNameCache,
                        flowDefCache,
                        businessContextCache))
                .toList();
    }

    public PageResult<WorkflowTaskResponse> getMyPendingTasks(Long userId, int pageNum) {
        return getMyPendingTasks(userId, pageNum, 10);
    }

    public PageResult<WorkflowTaskResponse> getMyPendingTasks(Long userId, int pageNum, int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        Pageable pageable = PageRequest.of(safePageNum - 1, safePageSize);
        Page<AuditInstance> pendingPage = workflowQueryRepository.findPendingAuditInstancesByUserId(userId, pageable);
        Map<Long, String> orgNameCache = new HashMap<>();
        Map<Long, String> userNameCache = new HashMap<>();
        Map<Long, AuditFlowDef> flowDefCache = new HashMap<>();
        Map<String, WorkflowBusinessContext> businessContextCache = new HashMap<>();
        primeReadModelCaches(pendingPage.getContent(), userNameCache, orgNameCache, businessContextCache);

        List<WorkflowTaskResponse> tasks = pendingPage.getContent().stream()
                .flatMap(instance -> instance.getStepInstances().stream()
                .filter(step -> AuditInstance.STEP_STATUS_PENDING.equals(step.getStatus()))
                .filter(step -> Objects.equals(step.getApproverId(), userId))
                .map(step -> enrichTaskResponse(instance, step, orgNameCache, userNameCache, flowDefCache, businessContextCache)))
                .sorted(Comparator.comparing(WorkflowTaskResponse::getCreatedTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return PageResult.of(tasks, pendingPage.getTotalElements(), safePageNum, safePageSize);
    }

    public long countMyPendingTasks(Long userId) {
        return workflowQueryRepository.countPendingTasksByUserId(userId);
    }

    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
     * 消息中心专用待办计数（独立方法，不改动 {@link #countMyPendingTasks} 的共享语义）：
     * 与列表严格同口径（本项目曾因计数与列表不一致出过 Issue #89）——
     * 同一 batch_id 的多个异动实例（一次「发起异动」= 一个批次）只计 1；
     * 无批次号的流程（PLAN 下发审批 / PLAN_REPORT 上报审批）行为不变，仍按实例计 1。
     */
    public long countMyPendingTodoMessages(Long userId) {
        List<WorkflowQueryRepository.PendingTaskIdentity> identities =
                workflowQueryRepository.findPendingTaskIdentitiesByUserId(userId);
        Set<String> pendingTodoGroupKeys = new java.util.LinkedHashSet<>();
        for (WorkflowQueryRepository.PendingTaskIdentity identity : identities) {
            pendingTodoGroupKeys.add(pendingTodoGroupKey(identity));
        }
        return pendingTodoGroupKeys.size();
    }

    /** 待办聚合分组键：有异动批次号的按批次归组，其余按实例归组。 */
    private String pendingTodoGroupKey(WorkflowQueryRepository.PendingTaskIdentity identity) {
        if (identity.batchId() != null && !identity.batchId().isBlank()) {
            return "batch:" + identity.batchId();
        }
        return "instance:" + identity.instanceId();
    }

    public List<WorkflowQueryRepository.PendingTaskIdentity> listPendingTaskIdentities(Long userId) {
        return workflowQueryRepository.findPendingTaskIdentitiesByUserId(userId);
    }

    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
     * 按异动批次号回查某用户名下的全部在途实例（逐实体一条），供消息中心
     * 重建「同 batch 合成 1 条」的待办消息详情。
     */
    public List<WorkflowTaskResponse> getMyPendingTasksByBatchId(Long userId, String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return List.of();
        }
        List<AuditInstance> instances =
                workflowQueryRepository.findPendingAuditInstancesByUserIdAndBatchId(userId, batchId);
        if (instances.isEmpty()) {
            return List.of();
        }
        Map<Long, String> orgNameCache = new HashMap<>();
        Map<Long, String> userNameCache = new HashMap<>();
        Map<Long, AuditFlowDef> flowDefCache = new HashMap<>();
        Map<String, WorkflowBusinessContext> businessContextCache = new HashMap<>();
        primeReadModelCaches(instances, userNameCache, orgNameCache, businessContextCache);
        return instances.stream()
                .flatMap(instance -> instance.getStepInstances().stream()
                        .filter(step -> AuditInstance.STEP_STATUS_PENDING.equals(step.getStatus()))
                        .filter(step -> Objects.equals(step.getApproverId(), userId))
                        .map(step -> enrichTaskResponse(instance, step, orgNameCache, userNameCache, flowDefCache, businessContextCache)))
                .sorted(Comparator.comparing(WorkflowTaskResponse::getCreatedTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public Optional<WorkflowTaskResponse> findMyPendingTaskById(Long userId, String taskId) {
        Long stepInstanceId = toLong(taskId);
        if (stepInstanceId == null) {
            return Optional.empty();
        }

        Map<Long, String> orgNameCache = new HashMap<>();
        Map<Long, String> userNameCache = new HashMap<>();
        Map<Long, AuditFlowDef> flowDefCache = new HashMap<>();
        Map<String, WorkflowBusinessContext> businessContextCache = new HashMap<>();

        return workflowQueryRepository.findPendingAuditInstanceByStepIdAndUserId(stepInstanceId, userId)
                .flatMap(instance -> instance.getStepInstances().stream()
                        .filter(step -> Objects.equals(step.getId(), stepInstanceId))
                        .filter(step -> AuditInstance.STEP_STATUS_PENDING.equals(step.getStatus()))
                        .filter(step -> Objects.equals(step.getApproverId(), userId))
                        .findFirst()
                        .map(step -> enrichTaskResponse(instance, step, orgNameCache, userNameCache, flowDefCache, businessContextCache)));
    }

    public PageResult<WorkflowInstanceResponse> getMyApprovedInstances(Long userId, int pageNum, int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        Page<AuditInstance> page = workflowQueryRepository.findApprovedAuditInstancesByUserId(
                userId,
                PageRequest.of(safePageNum - 1, safePageSize)
        );
        Map<Long, String> userNameCache = new HashMap<>();
        Map<Long, String> orgNameCache = new HashMap<>();
        Map<Long, AuditFlowDef> flowDefCache = new HashMap<>();
        Map<String, WorkflowBusinessContext> businessContextCache = new HashMap<>();
        primeReadModelCaches(page.getContent(), userNameCache, orgNameCache, businessContextCache);

        List<WorkflowInstanceResponse> items = page.getContent().stream()
                .map(instance -> buildInstanceSummary(instance, userNameCache, orgNameCache, flowDefCache, businessContextCache))
                .sorted(Comparator
                        .comparing(WorkflowInstanceResponse::getEndTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(WorkflowInstanceResponse::getStartTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(WorkflowInstanceResponse::getInstanceId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return PageResult.of(items, page.getTotalElements(), safePageNum, safePageSize);
    }

    public PageResult<WorkflowInstanceResponse> getMyAppliedInstances(Long userId, int pageNum, int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        Page<AuditInstance> page = workflowQueryRepository.findAppliedAuditInstancesByUserId(
                userId,
                PageRequest.of(safePageNum - 1, safePageSize)
        );
        Map<Long, String> userNameCache = new HashMap<>();
        Map<Long, String> orgNameCache = new HashMap<>();
        Map<Long, AuditFlowDef> flowDefCache = new HashMap<>();
        Map<String, WorkflowBusinessContext> businessContextCache = new HashMap<>();
        primeReadModelCaches(page.getContent(), userNameCache, orgNameCache, businessContextCache);

        List<WorkflowInstanceResponse> items = page.getContent().stream()
                .map(instance -> buildInstanceSummary(instance, userNameCache, orgNameCache, flowDefCache, businessContextCache))
                .sorted(Comparator
                        .comparing(WorkflowInstanceResponse::getStartTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(WorkflowInstanceResponse::getInstanceId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return PageResult.of(items, page.getTotalElements(), safePageNum, safePageSize);
    }

    private WorkflowInstanceDetailResponse buildInstanceDetail(AuditInstance instance) {
        Map<Long, String> userNameCache = new HashMap<>();
        Map<Long, String> orgNameCache = new HashMap<>();
        Map<Long, AuditFlowDef> flowDefCache = new HashMap<>();
        Map<String, WorkflowBusinessContext> businessContextCache = new HashMap<>();
        primeReadModelCaches(List.of(instance), userNameCache, orgNameCache, businessContextCache);

        WorkflowInstanceResponse summary = buildInstanceSummary(
                instance,
                userNameCache,
                orgNameCache,
                flowDefCache,
                businessContextCache
        );
        WorkflowInstanceDetailResponse response = new WorkflowInstanceDetailResponse();
        copySummary(summary, response);

        AuditFlowDef flowDef = resolveFlowDef(instance.getFlowDefId(), flowDefCache);
        Map<Long, AuditStepDef> stepDefById = flowDef == null || flowDef.getSteps() == null
                ? Map.of()
                : flowDef.getSteps().stream()
                .filter(step -> step.getId() != null)
                .collect(Collectors.toMap(AuditStepDef::getId, step -> step, (left, right) -> left, LinkedHashMap::new));

        List<WorkflowTaskResponse> tasks = instance.getStepInstances().stream()
                .sorted(Comparator.comparing(step -> step.getStepNo() == null ? Integer.MAX_VALUE : step.getStepNo()))
                .map(step -> enrichStepDetail(instance, step, stepDefById.get(step.getStepDefId()), userNameCache, orgNameCache))
                .toList();
        response.setTasks(tasks);
        response.setHistory(buildHistory(instance, userNameCache));
        return response;
    }

    private WorkflowInstanceResponse buildInstanceSummary(AuditInstance instance) {
        return buildInstanceSummary(instance, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    private WorkflowInstanceResponse buildInstanceSummary(
            AuditInstance instance,
            Map<Long, String> userNameCache,
            Map<Long, String> orgNameCache,
            Map<Long, AuditFlowDef> flowDefCache,
            Map<String, WorkflowBusinessContext> businessContextCache) {
        WorkflowInstanceResponse response = workflowReadModelMapper.toInstanceResponse(instance);
        WorkflowBusinessContext context = resolveBusinessContext(instance, orgNameCache, businessContextCache);
        AuditFlowDef flowDef = resolveFlowDef(instance.getFlowDefId(), flowDefCache);

        response.setEntityType(toExternalEntityType(instance.getEntityType()));
        response.setEntityId(instance.getEntityId());
        response.setBusinessEntityId(instance.getEntityId());
        response.setFlowCode(flowDef != null ? flowDef.getFlowCode() : null);
        response.setFlowName(flowDef != null ? flowDef.getFlowName() : null);
        response.setStarterName(resolveUserName(instance.getRequesterId(), userNameCache));
        response.setPlanId(context.planId());
        response.setPlanName(context.planName());
        response.setSourceOrgId(context.sourceOrgId());
        response.setSourceOrgName(context.sourceOrgName());
        response.setTargetOrgId(context.targetOrgId());
        response.setTargetOrgName(context.targetOrgName());
        response.setCurrentApproverName(resolveUserName(response.getCurrentApproverId(), userNameCache));
        return response;
    }

    private WorkflowTaskResponse enrichTaskResponse(
            AuditInstance instance,
            AuditStepInstance step,
            Map<Long, String> orgNameCache,
            Map<Long, String> userNameCache,
            Map<Long, AuditFlowDef> flowDefCache,
            Map<String, WorkflowBusinessContext> businessContextCache) {
        WorkflowTaskResponse response = workflowReadModelMapper.toPendingTaskResponse(instance, step);
        WorkflowBusinessContext context = resolveBusinessContext(instance, orgNameCache, businessContextCache);
        AuditFlowDef flowDef = resolveFlowDef(instance.getFlowDefId(), flowDefCache);
        response.setEntityType(toExternalEntityType(instance.getEntityType()));
        response.setEntityId(instance.getEntityId());
        response.setPlanId(context.planId());
        response.setPlanName(context.planName());
        response.setFlowCode(flowDef != null ? flowDef.getFlowCode() : null);
        response.setFlowName(flowDef != null ? flowDef.getFlowName() : null);
        response.setSourceOrgId(context.sourceOrgId());
        response.setSourceOrgName(context.sourceOrgName());
        response.setTargetOrgId(context.targetOrgId());
        response.setTargetOrgName(context.targetOrgName());
        // 2026-10-30 修复：上报链（PLAN_REPORT）的 sourceOrgName 是下发方向
        // （plan.createdByOrgId），不代表提交方；requesterOrgId 才是真实提交组织
        // （上报链=填报组织，下发/异动链=原下发方，两种链行为不变）。
        response.setRequesterOrgName(resolveOrgName(instance.getRequesterOrgId(), orgNameCache));
        response.setCurrentStepName(step.getStepName());
        response.setAssigneeName(resolveUserName(step.getApproverId(), userNameCache));
        response.setApproverOrgName(resolveOrgName(step.getApproverOrgId(), orgNameCache));
        // 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案
        response.setBatchId(instance.getBatchId());
        return response;
    }

    private WorkflowTaskResponse enrichStepDetail(
            AuditInstance instance,
            AuditStepInstance step,
            AuditStepDef stepDef,
            Map<Long, String> userNameCache,
            Map<Long, String> orgNameCache) {
        WorkflowTaskResponse response = workflowReadModelMapper.toTaskResponse(step);
        response.setInstanceId(instance.getId() != null ? instance.getId().toString() : null);
        response.setEntityType(toExternalEntityType(instance.getEntityType()));
        response.setEntityId(instance.getEntityId());
        response.setCurrentStepName(step.getStepName());
        response.setAssigneeName(resolveUserName(step.getApproverId(), userNameCache));
        response.setApproverOrgName(resolveOrgName(step.getApproverOrgId(), orgNameCache));
        response.setStepType(stepDef != null ? stepDef.getStepType() : null);
        return response;
    }

    private List<WorkflowHistoryResponse> buildHistory(AuditInstance instance, Map<Long, String> userNameCache) {
        List<WorkflowHistoryResponse> history = new ArrayList<>();

        for (AuditStepInstance step : instance.getStepInstances()) {
            if (AuditInstance.STEP_STATUS_APPROVED.equals(step.getStatus())) {
                WorkflowHistoryResponse item = workflowReadModelMapper.toHistoryResponse(instance, step, "APPROVE");
                item.setOperatorName(resolveUserName(step.getApproverId(), userNameCache));
                history.add(item);
            } else if (AuditInstance.STEP_STATUS_REJECTED.equals(step.getStatus())) {
                WorkflowHistoryResponse item = workflowReadModelMapper.toHistoryResponse(instance, step, "REJECT");
                item.setOperatorName(resolveUserName(step.getApproverId(), userNameCache));
                history.add(item);
            } else if (AuditInstance.STEP_STATUS_WITHDRAWN.equals(step.getStatus())) {
                WorkflowHistoryResponse item = workflowReadModelMapper.toHistoryResponse(instance, step, "WITHDRAW");
                item.setOperatorName(resolveUserName(step.getApproverId(), userNameCache));
                history.add(item);
            }
        }

        return history;
    }

    private WorkflowHistoryCardResponse buildHistoryCard(
            AuditInstance instance,
            int roundNo,
            Map<Long, String> userNameCache,
            Map<Long, String> orgNameCache,
            Map<Long, AuditFlowDef> flowDefCache,
            Map<String, WorkflowBusinessContext> businessContextCache) {
        WorkflowBusinessContext context = resolveBusinessContext(instance, orgNameCache, businessContextCache);
        AuditFlowDef flowDef = resolveFlowDef(instance.getFlowDefId(), flowDefCache);
        return WorkflowHistoryCardResponse.builder()
                .instanceId(instance.getId() != null ? instance.getId().toString() : null)
                .instanceNo(buildInstanceNo(toExternalEntityType(instance.getEntityType()), instance.getEntityId(), roundNo))
                .roundNo(roundNo)
                .entityType(toExternalEntityType(instance.getEntityType()))
                .entityId(instance.getEntityId())
                .planId(context.planId())
                .planName(context.planName())
                .flowCode(flowDef != null ? flowDef.getFlowCode() : null)
                .flowName(flowDef != null ? flowDef.getFlowName() : null)
                .sourceOrgId(context.sourceOrgId())
                .sourceOrgName(context.sourceOrgName())
                .targetOrgId(context.targetOrgId())
                .targetOrgName(context.targetOrgName())
                .status(instance.getStatus())
                .startedAt(instance.getStartedAt())
                .completedAt(instance.getCompletedAt())
                .requesterId(instance.getRequesterId())
                .requesterName(resolveUserName(instance.getRequesterId(), userNameCache))
                .build();
    }

    private Optional<AuditInstance> findLatestCurrentInstance(String entityType, Long entityId) {
        return findInstancesByBusiness(entityType, entityId).stream()
                .max(Comparator
                        .comparing(AuditInstance::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AuditInstance::getId, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private List<AuditInstance> findInstancesByBusiness(String entityType, Long entityId) {
        if (entityId == null || entityType == null || entityType.isBlank()) {
            return List.of();
        }
        return normalizeEntityTypes(entityType).stream()
                .flatMap(type -> auditInstanceRepository.findByBusinessTypeAndBusinessId(type, entityId).stream())
                .distinct()
                .toList();
    }

    private boolean isHistoryQualified(AuditInstance instance, Map<Long, AuditFlowDef> flowDefCache) {
        if (instance == null) {
            return false;
        }
        // 2026-10-30 用户定案（异动驳回后重报/取消闭环）：审批历史只认两种终态——
        // ① 整链走到最后节点且审批通过（既有口径，仍要求终审步骤已通过）；
        // ② 填报人「取消异动」主动终结（CANCELLED，completedAt=now）。
        // 驳回退回待重报（WITHDRAWN 且 completedAt 为空）是活链，可重新发起或取消，
        // 不进历史；发起人审批中撤回（WITHDRAWN 且 completedAt 非空）维持既有不进历史口径。
        if (AuditInstance.STATUS_CANCELLED.equalsIgnoreCase(instance.getStatus())) {
            return true;
        }
        if (!AuditInstance.STATUS_APPROVED.equalsIgnoreCase(instance.getStatus())) {
            return false;
        }
        AuditFlowDef flowDef = resolveFlowDef(instance.getFlowDefId(), flowDefCache);
        if (flowDef == null || flowDef.getSteps() == null) {
            return false;
        }

        Set<Long> terminalStepIds = flowDef.getSteps().stream()
                .filter(step -> Boolean.TRUE.equals(step.getIsTerminal()))
                .map(AuditStepDef::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (terminalStepIds.isEmpty()) {
            return false;
        }

        return instance.getStepInstances().stream()
                .anyMatch(step -> terminalStepIds.contains(step.getStepDefId())
                        && AuditInstance.STEP_STATUS_APPROVED.equalsIgnoreCase(step.getStatus()));
    }

    private WorkflowBusinessContext resolveBusinessContext(
            AuditInstance instance,
            Map<Long, String> orgNameCache,
            Map<String, WorkflowBusinessContext> businessContextCache) {
        if (instance == null) {
            return WorkflowBusinessContext.empty();
        }

        String cacheKey = String.join(":",
                String.valueOf(instance.getEntityType()),
                String.valueOf(instance.getEntityId()));
        WorkflowBusinessContext cached = businessContextCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        WorkflowBusinessContext resolved = resolveSummary(instance.getEntityType(), instance.getEntityId())
                .map(summary -> {
                    if (summary.sourceOrgId() != null && summary.sourceOrgName() != null) {
                        orgNameCache.put(summary.sourceOrgId(), summary.sourceOrgName());
                    }
                    if (summary.targetOrgId() != null && summary.targetOrgName() != null) {
                        orgNameCache.put(summary.targetOrgId(), summary.targetOrgName());
                    }
                    return new WorkflowBusinessContext(
                            summary.planId(),
                            summary.planName(),
                            summary.sourceOrgId(),
                            summary.sourceOrgName(),
                            summary.targetOrgId(),
                            summary.targetOrgName(),
                            summary.displayName()
                    );
                })
                .orElse(WorkflowBusinessContext.empty());
        businessContextCache.put(cacheKey, resolved);
        return resolved;
    }

    private void primeReadModelCaches(
            List<AuditInstance> instances,
            Map<Long, String> userNameCache,
            Map<Long, String> orgNameCache,
            Map<String, WorkflowBusinessContext> businessContextCache) {
        if (instances == null || instances.isEmpty()) {
            return;
        }

        Set<Long> orgIds = new java.util.LinkedHashSet<>();
        Set<Long> userIds = new java.util.LinkedHashSet<>();

        for (AuditInstance instance : instances) {
            if (instance == null) {
                continue;
            }
            if (instance.getRequesterId() != null) {
                userIds.add(instance.getRequesterId());
            }
            if (instance.getRequesterOrgId() != null) {
                orgIds.add(instance.getRequesterOrgId());
            }
            if (instance.getStepInstances() != null) {
                for (AuditStepInstance step : instance.getStepInstances()) {
                    if (step.getApproverId() != null) {
                        userIds.add(step.getApproverId());
                    }
                    if (step.getApproverOrgId() != null) {
                        orgIds.add(step.getApproverOrgId());
                    }
                }
            }
        }

        userIds.forEach(userId -> userProvider.findIdentity(userId).ifPresent(user -> {
            if (user.id() != null) {
                String userName = user.realName() != null && !user.realName().isBlank()
                        ? user.realName()
                        : (user.username() == null || user.username().isBlank() ? "User#" + user.id() : user.username());
                userNameCache.put(user.id(), userName);
            }
        }));

        for (AuditInstance instance : instances) {
            if (instance == null) {
                continue;
            }
            resolveBusinessContext(instance, orgNameCache, businessContextCache);
        }
    }

    private String buildBusinessContextKey(String entityType, Long entityId) {
        return String.join(":", String.valueOf(entityType), String.valueOf(entityId));
    }

    private AuditFlowDef resolveFlowDef(Long flowDefId, Map<Long, AuditFlowDef> flowDefCache) {
        if (flowDefId == null) {
            return null;
        }
        if (flowDefCache.containsKey(flowDefId)) {
            return flowDefCache.get(flowDefId);
        }
        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefById(flowDefId);
        flowDefCache.put(flowDefId, flowDef);
        return flowDef;
    }

    private String resolveUserName(Long userId, Map<Long, String> userNameCache) {
        if (userId == null) {
            return null;
        }
        if (userNameCache.containsKey(userId)) {
            return userNameCache.get(userId);
        }
        String userName = userProvider.findIdentity(userId)
                .map(user -> user.realName() != null && !user.realName().isBlank()
                        ? user.realName()
                        : (user.username() == null || user.username().isBlank() ? "User#" + userId : user.username()))
                .orElse("User#" + userId);
        userNameCache.put(userId, userName);
        return userName;
    }

    private String resolveOrgName(Long orgId, Map<Long, String> orgNameCache) {
        if (orgId == null) {
            return null;
        }
        if (orgNameCache.containsKey(orgId)) {
            return orgNameCache.get(orgId);
        }
        String orgName = "Org#" + orgId;
        orgNameCache.put(orgId, orgName);
        return orgName;
    }

    private Optional<WorkflowBusinessContextPort.BusinessSummary> resolveSummary(String entityType, Long entityId) {
        for (WorkflowBusinessContextPort contextPort : workflowBusinessContextPorts) {
            Optional<WorkflowBusinessContextPort.BusinessSummary> summary = contextPort.getBusinessSummary(entityType, entityId);
            if (summary.isPresent()) {
                return summary;
            }
        }
        return Optional.empty();
    }

    private List<String> normalizeEntityTypes(String entityType) {
        if (isPlanReportEntityType(entityType)) {
            return List.of(PLAN_REPORT_ENTITY_TYPE, LEGACY_PLAN_REPORT_ENTITY_TYPE);
        }
        return List.of(entityType.toUpperCase());
    }

    private String toExternalEntityType(String entityType) {
        return isPlanReportEntityType(entityType) ? PLAN_REPORT_ENTITY_TYPE : entityType;
    }

    private boolean isPlanReportEntityType(String entityType) {
        return PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType)
                || LEGACY_PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType);
    }

    private String buildInstanceNo(String entityType, Long entityId, int roundNo) {
        return "%s-%s-%03d".formatted(entityType, entityId, Math.max(roundNo, 0));
    }

    private void copySummary(WorkflowInstanceResponse source, WorkflowInstanceDetailResponse target) {
        target.setInstanceId(source.getInstanceId());
        target.setDefinitionId(source.getDefinitionId());
        target.setStatus(source.getStatus());
        target.setEntityType(source.getEntityType());
        target.setEntityId(source.getEntityId());
        target.setBusinessEntityId(source.getBusinessEntityId());
        target.setFlowCode(source.getFlowCode());
        target.setFlowName(source.getFlowName());
        target.setStarterId(source.getStarterId());
        target.setStarterName(source.getStarterName());
        target.setPlanId(source.getPlanId());
        target.setPlanName(source.getPlanName());
        target.setSourceOrgId(source.getSourceOrgId());
        target.setSourceOrgName(source.getSourceOrgName());
        target.setTargetOrgId(source.getTargetOrgId());
        target.setTargetOrgName(source.getTargetOrgName());
        target.setStartTime(source.getStartTime());
        target.setEndTime(source.getEndTime());
        target.setCurrentTaskId(source.getCurrentTaskId());
        target.setCurrentStepName(source.getCurrentStepName());
        target.setCurrentApproverId(source.getCurrentApproverId());
        target.setCurrentApproverName(source.getCurrentApproverName());
        target.setCanWithdraw(source.getCanWithdraw());
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int normalizePageNum(int pageNum) {
        return Math.max(pageNum, 1);
    }

    private int normalizePageSize(int pageSize) {
        return Math.max(pageSize, 1);
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

    private record WorkflowBusinessContext(
            Long planId,
            String planName,
            Long sourceOrgId,
            String sourceOrgName,
            Long targetOrgId,
            String targetOrgName,
            String displayName
    ) {
        private static WorkflowBusinessContext empty() {
            return new WorkflowBusinessContext(null, null, null, null, null, null, null);
        }
    }
}
