package com.sism.strategy.application;

import com.sism.exception.ConflictException;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.shared.domain.model.base.DomainEvent;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import com.sism.strategy.domain.cycle.Cycle;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.indicator.event.IndicatorCreatedEvent;
import com.sism.strategy.domain.plan.event.PlanCreatedEvent;
import com.sism.strategy.domain.plan.event.PlanSubmittedForApprovalEvent;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.plan.PlanStatus;
import com.sism.strategy.domain.repository.CycleRepository;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.interfaces.dto.CreatePlanRequest;
import com.sism.strategy.interfaces.dto.MilestoneResponse;
import com.sism.strategy.interfaces.dto.PlanResponse;
import com.sism.strategy.interfaces.dto.SubmitPlanApprovalRequest;
import com.sism.strategy.interfaces.dto.UpdatePlanRequest;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PlanApplicationService - 计划应用服务
 * 处理计划的业务逻辑，包括计划的创建、更新、查询等操作
 */
@Service("strategyPlanApplicationService")
@Slf4j
public class PlanApplicationService {
    private final PlanRepository planRepository;
    private final CycleRepository cycleRepository;
    private final IndicatorRepository indicatorRepository;
    private final BasicTaskWeightValidationService basicTaskWeightValidationService;
    private final TaskRepository taskRepository;
    private final DomainEventPublisher eventPublisher;
    private final PlanWorkflowSnapshotQueryService planWorkflowSnapshotQueryService;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PlanIntegrityService planIntegrityService;
    private final StrategyOrgProperties strategyOrgProperties;
    private final PlanWorkflowRuntimeService planWorkflowRuntimeService;
    private final MilestoneApplicationService milestoneApplicationService;
    private final ObjectProvider<PlanApplicationService> selfProvider;

    @Autowired
    public PlanApplicationService(
            PlanRepository planRepository,
            CycleRepository cycleRepository,
            IndicatorRepository indicatorRepository,
            BasicTaskWeightValidationService basicTaskWeightValidationService,
            TaskRepository taskRepository,
            DomainEventPublisher eventPublisher,
            PlanWorkflowSnapshotQueryService planWorkflowSnapshotQueryService,
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            PlanIntegrityService planIntegrityService,
            StrategyOrgProperties strategyOrgProperties,
            PlanWorkflowRuntimeService planWorkflowRuntimeService,
            MilestoneApplicationService milestoneApplicationService,
            ObjectProvider<PlanApplicationService> selfProvider
    ) {
        this.planRepository = planRepository;
        this.cycleRepository = cycleRepository;
        this.indicatorRepository = indicatorRepository;
        this.basicTaskWeightValidationService = basicTaskWeightValidationService;
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
        this.planWorkflowSnapshotQueryService = planWorkflowSnapshotQueryService;
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.planIntegrityService = planIntegrityService;
        this.strategyOrgProperties = strategyOrgProperties;
        this.planWorkflowRuntimeService = planWorkflowRuntimeService;
        this.milestoneApplicationService = milestoneApplicationService;
        this.selfProvider = selfProvider;
    }

    public PlanApplicationService(PlanRepository planRepository,
                                  CycleRepository cycleRepository,
                                  IndicatorRepository indicatorRepository,
                                  BasicTaskWeightValidationService basicTaskWeightValidationService,
                                  TaskRepository taskRepository,
                                  DomainEventPublisher eventPublisher,
                                  PlanWorkflowSnapshotQueryService planWorkflowSnapshotQueryService,
                                  JdbcTemplate jdbcTemplate,
                                  NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                                  PlatformTransactionManager ignoredTransactionManager,
                                  PlanIntegrityService planIntegrityService,
                                  StrategyOrgProperties strategyOrgProperties) {
        this.planRepository = planRepository;
        this.cycleRepository = cycleRepository;
        this.indicatorRepository = indicatorRepository;
        this.basicTaskWeightValidationService = basicTaskWeightValidationService;
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
        this.planWorkflowSnapshotQueryService = planWorkflowSnapshotQueryService;
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.planIntegrityService = planIntegrityService;
        this.strategyOrgProperties = strategyOrgProperties;
        this.planWorkflowRuntimeService = new PlanWorkflowRuntimeService(
                jdbcTemplate,
                planRepository,
                strategyOrgProperties
        );
        this.milestoneApplicationService = null;
        this.selfProvider = new ObjectProvider<>() {
            @Override
            public PlanApplicationService getObject(Object... args) {
                return PlanApplicationService.this;
            }

            @Override
            public PlanApplicationService getIfAvailable() {
                return PlanApplicationService.this;
            }

            @Override
            public PlanApplicationService getIfUnique() {
                return PlanApplicationService.this;
            }

            @Override
            public PlanApplicationService getObject() {
                return PlanApplicationService.this;
            }

            @Override
            public java.util.Iterator<PlanApplicationService> iterator() {
                return Collections.singletonList(PlanApplicationService.this).iterator();
            }
        };
    }

    /**
     * 创建计划
     */
    @Transactional
    public PlanResponse createPlan(CreatePlanRequest request) {
        // 验证周期是否存在
        Cycle cycle = requireCycle(request.getCycleId());

        // 确定计划层级
        PlanLevel planLevel = determinePlanLevel(request.getPlanType());

        // 默认使用当前组织ID作为创建者和目标组织
        Long createdByOrgId = request.getCreatedByOrgId() != null
                ? request.getCreatedByOrgId()
                : 1L; // 默认值

        Long targetOrgId = request.getTargetOrgId() != null
                ? request.getTargetOrgId()
                : 1L; // 默认值

        assertNoActivePlanConflict(request.getCycleId(), planLevel, createdByOrgId, targetOrgId, null);

        Plan plan = Plan.create(
                request.getCycleId(),
                targetOrgId,
                createdByOrgId,
                planLevel
        );

        Plan saved = savePlanHandlingConflict(plan);
        eventPublisher.publish(new PlanCreatedEvent(
                saved.getId(),
                saved.getPlanLevel().name(),
                saved.getTargetOrgId()
        ));
        return convertToResponse(saved, cycle.getYear().toString(), loadOrgNamesById(saved), loadPlanMetrics(List.of(saved)));
    }

    /**
     * 更新计划
     */
    @Transactional
    public PlanResponse updatePlan(Long id, UpdatePlanRequest request) {
        Plan plan = requirePlan(id);

        Long nextTargetOrgId = request.getTargetOrgId() != null ? request.getTargetOrgId() : plan.getTargetOrgId();
        Long nextCreatedByOrgId = request.getCreatedByOrgId() != null ? request.getCreatedByOrgId() : plan.getCreatedByOrgId();
        assertNoActivePlanConflict(plan.getCycleId(), plan.getPlanLevel(), nextCreatedByOrgId, nextTargetOrgId, plan.getId());

        if (request.getTargetOrgId() != null) {
            plan.setTargetOrgId(request.getTargetOrgId());
        }

        if (request.getCreatedByOrgId() != null) {
            plan.setCreatedByOrgId(request.getCreatedByOrgId());
        }

        Plan updated = savePlanHandlingConflict(plan);
        return convertToResponse(updated, null, loadOrgNamesById(updated), loadPlanMetrics(List.of(updated)));
    }

    /**
     * 删除计划
     */
    @Transactional
    public void deletePlan(Long id) {
        Plan plan = requirePlan(id);

        planRepository.delete(plan);
    }

    /**
     * 发布计划（下发）
     * 同时同步所有关联指标的状态为 DISTRIBUTED
     */
    @Transactional
    public PlanResponse publishPlan(Long id) {
        Plan plan = requirePlan(id);

        basicTaskWeightValidationService.validatePlanBasicWeight(plan.getId(), plan.getTargetOrgId());
        plan.activate();
        Plan saved = planRepository.save(plan);
        publishAndClearEvents(saved);

        // 同步所有关联指标的状态
        syncIndicatorStatusWithPlan(saved);

        return convertToResponse(saved, null, loadOrgNamesById(saved), loadPlanMetrics(List.of(saved)));
    }

    /**
     * 提交计划审批
     */
    public PlanResponse submitPlanForApproval(Long id,
                                              SubmitPlanApprovalRequest request,
                                              Long currentUserId,
                                              Long currentOrgId) {
        Plan saved = selfProvider.getObject().submitPlanForApprovalInTransaction(
                id,
                request,
                currentUserId,
                currentOrgId);
        PlanWorkflowSnapshotQueryService.WorkflowSnapshot refreshedSnapshot =
                awaitWorkflowSnapshot(saved.getId());
        return enrichWorkflowFields(convertToResponse(saved, null, loadOrgNamesById(saved), loadPlanMetrics(List.of(saved))), refreshedSnapshot);
    }

    @Transactional
    public Plan submitPlanForApprovalInTransaction(Long id,
                                                   SubmitPlanApprovalRequest request,
                                                   Long currentUserId,
                                                   Long currentOrgId) {
        Plan plan = requirePlan(id);
        PlanWorkflowSnapshotQueryService.WorkflowSnapshot existingSnapshot =
                planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(id);

        plan.submitForApproval(allowsDistributedSubmission(request));
        Plan saved = planRepository.save(plan);
        publishAndClearEvents(saved);
        boolean resumedWithdrawnWorkflow = planWorkflowRuntimeService.reactivateWithdrawnWorkflowCurrentStep(
                existingSnapshot == null ? null : existingSnapshot.getWorkflowInstanceId(),
                request == null ? null : request.getComment());
        if (!resumedWithdrawnWorkflow) {
            eventPublisher.publish(new PlanSubmittedForApprovalEvent(
                    saved.getId(),
                    request.getWorkflowCode(),
                    currentUserId,
                    currentOrgId,
                    request.getComment()
            ));
        }
        return saved;
    }

    private boolean allowsDistributedSubmission(SubmitPlanApprovalRequest request) {
        if (request == null || request.getWorkflowCode() == null) {
            return false;
        }

        return isApprovalWorkflowCode(request.getWorkflowCode());
    }

    private boolean isApprovalWorkflowCode(String workflowCode) {
        if (workflowCode == null) {
            return false;
        }

        String normalized = workflowCode.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("PLAN_APPROVAL_");
    }

    /**
     * 审批通过计划
     * 同时同步所有关联指标的状态为 DISTRIBUTED
     */
    @Transactional
    public PlanResponse approvePlan(Long id) {
        Plan plan = requirePlan(id);

        basicTaskWeightValidationService.validatePlanBasicWeight(plan.getId(), plan.getTargetOrgId());
        plan.approve();
        Plan saved = planRepository.save(plan);
        publishAndClearEvents(saved);

        // 同步所有关联指标的状态
        syncIndicatorStatusWithPlan(saved);

        return convertToResponse(saved, null, loadOrgNamesById(saved), loadPlanMetrics(List.of(saved)));
    }

    /**
     * 驳回计划
     */
    @Transactional
    public PlanResponse rejectPlan(Long id) {
        Plan plan = requirePlan(id);

        plan.returnForRevision();
        Plan saved = planRepository.save(plan);
        publishAndClearEvents(saved);
        return convertToResponse(saved, null, loadOrgNamesById(saved), loadPlanMetrics(List.of(saved)));
    }

    /**
     * 撤回计划到草稿
     */
    @Transactional
    public PlanResponse withdrawPlan(Long id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + id));

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot workflowSnapshot =
                planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(id);
        if (workflowSnapshot == null || !Boolean.TRUE.equals(workflowSnapshot.getCanWithdraw())) {
            throw new IllegalStateException("Plan is not in a withdrawable workflow state");
        }

        planWorkflowRuntimeService.withdrawWorkflowCurrentStep(workflowSnapshot.getWorkflowInstanceId());
        plan.withdraw();
        Plan saved = planRepository.save(plan);
        publishAndClearEvents(saved);
        syncIndicatorStatusWithPlan(saved);
        PlanWorkflowSnapshotQueryService.WorkflowSnapshot refreshedSnapshot =
                planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(id);
        return enrichWorkflowFields(convertToResponse(saved, null, loadOrgNamesById(saved), loadPlanMetrics(List.of(saved))), refreshedSnapshot);
    }

    @Transactional
    public void markWorkflowApproved(Long planId) {
        planRepository.findById(planId).ifPresent(plan -> {
            basicTaskWeightValidationService.validatePlanBasicWeight(plan.getId(), plan.getTargetOrgId());
            plan.approve();
            Plan saved = planRepository.save(plan);
            publishAndClearEvents(saved);
            syncIndicatorStatusWithPlan(saved);
        });
    }

    @Transactional
    public void markWorkflowPending(Long planId) {
        planRepository.findById(planId).ifPresent(plan -> {
            if (!PlanStatus.PENDING.value().equals(plan.getStatus())) {
                plan.submitForApproval(true);
                Plan saved = planRepository.save(plan);
                publishAndClearEvents(saved);
            }
        });
    }

    @Transactional
    public void markWorkflowRejected(Long planId, String reason) {
        planRepository.findById(planId).ifPresent(plan -> {
            plan.returnForRevision();
            Plan saved = planRepository.save(plan);
            publishAndClearEvents(saved);
        });
    }

    @Transactional
    public void markWorkflowWithdrawn(Long planId) {
        planRepository.findById(planId).ifPresent(plan -> {
            plan.withdraw();
            Plan saved = planRepository.save(plan);
            publishAndClearEvents(saved);
            syncIndicatorStatusWithPlan(saved);
        });
    }

    private record PlanMetrics(Map<Long, Integer> indicatorCounts,
                               Map<Long, Integer> milestoneCounts,
                               Map<Long, Integer> completionPercentages) {
        private static PlanMetrics empty() {
            return new PlanMetrics(Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * 归档计划
     */
    @Transactional
    public PlanResponse archivePlan(Long id) {
        Plan plan = requirePlan(id);

        plan.archive();
        Plan saved = planRepository.save(plan);
        publishAndClearEvents(saved);
        return convertToResponse(saved, null, loadOrgNamesById(saved), loadPlanMetrics(List.of(saved)));
    }

    private void publishAndClearEvents(Plan plan) {
        if (plan == null) {
            return;
        }
        List<DomainEvent> events = plan.getDomainEvents();
        for (DomainEvent event : events) {
            eventPublisher.publish(event);
        }
        plan.clearEvents();
    }

    /**
     * 根据ID查询计划
     */
    public Optional<PlanResponse> getPlanById(Long id) {
        planIntegrityService.ensurePlanMatrix();
        return planRepository.findById(id)
                .map(plan -> enrichWorkflowFields(
                        convertToResponse(plan, null, loadOrgNamesById(plan), loadPlanMetrics(List.of(plan))),
                        plan));
    }

    /**
     * 根据Task ID查询关联的Plan
     * Task 与 Plan 通过 sys_task.plan_id 关联。
     */
    public Optional<PlanResponse> getPlanByTaskId(Long taskId) {
        planIntegrityService.ensurePlanMatrix();
        return taskRepository.findById(taskId)
                .map(com.sism.task.domain.task.StrategicTask::getPlanId)
                .flatMap(planRepository::findById)
                .map(plan -> enrichWorkflowFields(
                        convertToResponse(plan, null, loadOrgNamesById(plan), loadPlanMetrics(List.of(plan))),
                        plan));
    }

    /**
     * 查询所有计划
     */
    public List<PlanResponse> getAllPlans() {
        planIntegrityService.ensurePlanMatrix();
        List<Plan> plans = planRepository.findAll();
        Map<Long, String> orgNamesById = loadOrgNamesById(plans);
        PlanMetrics planMetrics = loadPlanMetrics(plans);
        Map<Long, PlanWorkflowSnapshotQueryService.WorkflowSnapshot> workflowSnapshotsByPlanId =
                planWorkflowSnapshotQueryService.getWorkflowSnapshotsByPlanIds(
                        plans.stream().map(Plan::getId).toList()
                );
        return plans.stream()
                .map(plan -> enrichWorkflowFields(
                        convertToResponse(plan, null, orgNamesById, planMetrics),
                        workflowSnapshotsByPlanId.get(plan.getId())))
                .collect(Collectors.toList());
    }

    /**
     * 分页查询计划
     */
    public Page<PlanResponse> getPlans(int page, int size, Integer year, String status) {
        planIntegrityService.ensurePlanMatrix();
        long startedAt = System.currentTimeMillis();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Long> cycleIds = year == null
                ? List.of()
                : cycleRepository.findByYear(year).stream()
                .map(Cycle::getId)
                .toList();

        if (year != null && cycleIds.isEmpty()) {
            log.info(
                    "Loaded plans page={}, size={}, year={}, status={}, results=0, total=0, durationMs={}",
                    page,
                    size,
                    year,
                    status,
                    System.currentTimeMillis() - startedAt
            );
            return Page.empty(pageable);
        }

        List<String> queryStatuses = (status == null || status.isBlank())
                ? List.of()
                : PlanStatus.expandQueryStatuses(status);

        Page<Plan> planPage = planRepository.findPage(cycleIds, queryStatuses, pageable);
        Map<Long, String> orgNamesById = loadOrgNamesById(planPage.getContent());
        PlanMetrics planMetrics = loadPlanMetrics(planPage.getContent());
        Map<Long, PlanWorkflowSnapshotQueryService.WorkflowSnapshot> workflowSnapshotsByPlanId =
                safeLoadWorkflowSnapshotsByPlanIds(planPage.getContent().stream().map(Plan::getId).toList());
        Page<PlanResponse> responsePage = planPage.map(
                plan -> enrichWorkflowFields(
                        convertToResponse(plan, null, orgNamesById, planMetrics),
                        workflowSnapshotsByPlanId.get(plan.getId())));

        log.info(
                "Loaded plans page={}, size={}, year={}, status={}, results={}, total={}, durationMs={}",
                page,
                size,
                year,
                status,
                responsePage.getNumberOfElements(),
                responsePage.getTotalElements(),
                System.currentTimeMillis() - startedAt
        );
        return responsePage;
    }

    /**
     * 根据周期ID查询计划
     */
    public List<PlanResponse> getPlansByCycle(Long cycleId) {
        planIntegrityService.ensurePlanMatrix();
        Cycle cycle = requireCycle(cycleId);

        List<Plan> plans = planRepository.findByCycleId(cycleId);
        Map<Long, String> orgNamesById = loadOrgNamesById(plans);
        PlanMetrics planMetrics = loadPlanMetrics(plans);
        Map<Long, PlanWorkflowSnapshotQueryService.WorkflowSnapshot> workflowSnapshotsByPlanId =
                safeLoadWorkflowSnapshotsByPlanIds(plans.stream().map(Plan::getId).toList());
        return plans.stream()
                .map(plan -> enrichWorkflowFields(
                        convertToResponse(plan, cycle.getYear().toString(), orgNamesById, planMetrics),
                        workflowSnapshotsByPlanId.get(plan.getId())))
                .collect(Collectors.toList());
    }

    /**
     * 获取计划详情（包含指标和里程碑）
     */
    public PlanDetailsResponse getPlanDetails(Long id) {
        planIntegrityService.ensurePlanMatrix();
        long startedAt = System.currentTimeMillis();
        Plan plan = requirePlan(id);

        Cycle cycle = cycleRepository.findById(plan.getCycleId()).orElse(null);

        PlanMetrics planMetrics = loadPlanMetrics(List.of(plan));
        PlanResponse planResponse = convertToResponse(
                plan,
                cycle != null ? cycle.getYear().toString() : null,
                loadOrgNamesById(plan),
                planMetrics
        );
        planResponse = enrichWorkflowFields(planResponse, plan);

        PlanDetailsResponse details = new PlanDetailsResponse();
        details.setId(planResponse.getId());
        details.setPlanName(planResponse.getPlanName());
        details.setDescription(planResponse.getDescription());
        details.setPlanType(planResponse.getPlanType());
        details.setStatus(planResponse.getStatus());
        details.setStartDate(planResponse.getStartDate());
        details.setEndDate(planResponse.getEndDate());
        details.setOwnerDepartment(planResponse.getOwnerDepartment());
        details.setCompletionPercentage(planResponse.getCompletionPercentage());
        details.setIndicatorCount(planResponse.getIndicatorCount());
        details.setMilestoneCount(planResponse.getMilestoneCount());
        details.setCreateTime(planResponse.getCreateTime());
        details.setYear(planResponse.getYear());
        details.setCycleId(planResponse.getCycleId());
        details.setTargetOrgId(planResponse.getTargetOrgId());
        details.setTargetOrgName(planResponse.getTargetOrgName());
        details.setCreatedByOrgId(planResponse.getCreatedByOrgId());
        details.setCreatedByOrgName(planResponse.getCreatedByOrgName());
        details.setPlanLevel(planResponse.getPlanLevel());
        details.setCanEdit(planResponse.getCanEdit());
        details.setCanResubmit(planResponse.getCanResubmit());
        details.setWorkflowInstanceId(planResponse.getWorkflowInstanceId());
        details.setWorkflowStatus(planResponse.getWorkflowStatus());
        details.setCurrentStepName(planResponse.getCurrentStepName());
        details.setCurrentApproverId(planResponse.getCurrentApproverId());
        details.setCurrentApproverName(planResponse.getCurrentApproverName());
        details.setCanWithdraw(planResponse.getCanWithdraw());
        List<MilestoneResponse> milestones = milestoneApplicationService == null
                ? List.of()
                : milestoneApplicationService.getMilestonesByPlanId(plan.getId());
        details.setMilestones(milestones.stream()
                .map(this::toInternalMilestoneResponse)
                .collect(Collectors.toList()));

        String planStatus = PlanStatus.fromRaw(plan.getStatus()).value();
        List<Indicator> planIndicators = loadPlanIndicators(plan);
        Map<Long, String> taskNamesById = loadTaskNamesById(planIndicators);
        List<Long> indicatorIds = planIndicators.stream()
                .map(Indicator::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        Map<Long, Integer> reportProgressByIndicatorId = getLatestReportProgressByIndicatorIds(indicatorIds);
        CurrentReportContext currentReportContext = getCurrentReportContext(plan.getId(), indicatorIds);

        List<InternalIndicatorResponse> indicators = planIndicators.stream()
                .map(indicator -> convertIndicatorToResponse(
                        indicator,
                        planStatus,
                        reportProgressByIndicatorId.get(indicator.getId()),
                        currentReportContext,
                        taskNamesById))
                .collect(Collectors.toList());
        details.setIndicators(indicators);
        details.setWorkflowHistory(planWorkflowSnapshotQueryService.getWorkflowHistoryByPlanId(plan.getId()));

        log.info(
                "Loaded plan details id={}, indicators={}, durationMs={}",
                id,
                indicators.size(),
                System.currentTimeMillis() - startedAt
        );
        return details;
    }

    /**
     * 确定计划层级
     */
    private PlanLevel determinePlanLevel(String planType) {
        if (planType == null) {
            return PlanLevel.STRAT_TO_FUNC;
        }

        String typeUpper = planType.toUpperCase();
        if (typeUpper.equals("OPERATION") || typeUpper.equals("OPERATIONAL")) {
            return PlanLevel.FUNC_TO_COLLEGE;
        } else if (typeUpper.equals("COMPREHENSIVE")) {
            return PlanLevel.FUNC_TO_COLLEGE;
        } else {
            return PlanLevel.STRAT_TO_FUNC;
        }
    }

    /**
     * 将Plan实体转换为响应DTO
     */
    private PlanResponse convertToResponse(Plan plan, String year) {
        return convertToResponse(plan, year, loadOrgNamesById(plan), loadPlanMetrics(plan == null ? List.of() : List.of(plan)));
    }

    private PlanResponse convertToResponse(Plan plan, String year, Map<Long, String> orgNamesById) {
        return convertToResponse(plan, year, orgNamesById, loadPlanMetrics(plan == null ? List.of() : List.of(plan)));
    }

    private PlanResponse convertToResponse(Plan plan,
                                           String year,
                                           Map<Long, String> orgNamesById,
                                           PlanMetrics metricsByPlanId) {
        String targetOrgName = plan.getTargetOrgId() == null ? null : orgNamesById.get(plan.getTargetOrgId());
        String createdByOrgName = plan.getCreatedByOrgId() == null ? null : orgNamesById.get(plan.getCreatedByOrgId());
        int indicatorCount = metricsByPlanId.indicatorCounts().getOrDefault(plan.getId(), 0);
        int milestoneCount = metricsByPlanId.milestoneCounts().getOrDefault(plan.getId(), 0);
        int completionPercentage = metricsByPlanId.completionPercentages().getOrDefault(plan.getId(), 0);

        return PlanResponse.builder()
                .id(plan.getId())
                .planName("Plan " + plan.getId()) // 计划名称需要从Plan实体获取或单独存储
                .description(null) // 需要从Plan实体获取或单独存储
                .planType(plan.getPlanLevel() != null ? plan.getPlanLevel().name() : "STRATEGY")
                .status(PlanStatus.fromRaw(plan.getStatus()).value())
                .startDate(plan.getCreatedAt())
                .endDate(plan.getUpdatedAt())
                .ownerDepartment(createdByOrgName)
                .completionPercentage(completionPercentage)
                .indicatorCount(indicatorCount)
                .milestoneCount(milestoneCount)
                .createTime(plan.getCreatedAt())
                .year(year)
                .cycleId(plan.getCycleId())
                .targetOrgId(plan.getTargetOrgId())
                .targetOrgName(targetOrgName) // 设置目标组织名称
                .createdByOrgId(plan.getCreatedByOrgId())
                .createdByOrgName(createdByOrgName)
                .planLevel(plan.getPlanLevel() != null ? plan.getPlanLevel().name() : null)
                .canEdit(plan.isEditable())
                .canResubmit(plan.isEditable())
                .workflowStatus(null)
                .currentStepName(null)
                .currentApproverId(null)
                .currentApproverName(null)
                .canWithdraw(null)
                .build();
    }

    private PlanResponse enrichWorkflowFields(PlanResponse response, Plan plan) {
        if (response == null || plan == null || plan.getId() == null) {
            return response;
        }

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot workflowSnapshot =
                planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(plan.getId());
        return enrichWorkflowFields(response, workflowSnapshot);
    }

    private PlanResponse enrichWorkflowFields(PlanResponse response,
                                              PlanWorkflowSnapshotQueryService.WorkflowSnapshot workflowSnapshot) {
        if (response == null) {
            return response;
        }
        if (workflowSnapshot == null) {
            return response;
        }

        response.setWorkflowInstanceId(workflowSnapshot.getWorkflowInstanceId());
        response.setSubmittedBy(workflowSnapshot.getStarterId());
        response.setSubmittedByName(workflowSnapshot.getStarterName());
        response.setSubmittedAt(workflowSnapshot.getStartedAt());
        response.setLastRejectReason(workflowSnapshot.getLastRejectReason());
        response.setWorkflowStatus(workflowSnapshot.getWorkflowStatus());
        response.setCurrentStepName(workflowSnapshot.getCurrentStepName());
        response.setCurrentApproverId(workflowSnapshot.getCurrentApproverId());
        response.setCurrentApproverName(workflowSnapshot.getCurrentApproverName());
        response.setCanWithdraw(workflowSnapshot.getCanWithdraw());
        return response;
    }

    private PlanWorkflowSnapshotQueryService.WorkflowSnapshot awaitWorkflowSnapshot(Long planId) {
        if (planId == null) {
            return null;
        }

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot latestSnapshot =
                planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(planId);
        if (isReadyForSubmitResponse(latestSnapshot)) {
            return latestSnapshot;
        }
        return planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(planId);
    }

    private boolean isReadyForSubmitResponse(PlanWorkflowSnapshotQueryService.WorkflowSnapshot snapshot) {
        return snapshot != null
                && snapshot.getWorkflowInstanceId() != null
                && snapshot.getCurrentStepName() != null
                && Boolean.TRUE.equals(snapshot.getCanWithdraw());
    }

    private Map<Long, String> loadOrgNamesById(Plan plan) {
        return plan == null ? Map.of() : loadOrgNamesById(List.of(plan));
    }

    private Map<Long, String> loadOrgNamesById(Collection<Plan> plans) {
        if (plans == null || plans.isEmpty()) {
            return Map.of();
        }

        List<Long> orgIds = plans.stream()
                .flatMap(plan -> Stream.<Long>of(plan.getTargetOrgId(), plan.getCreatedByOrgId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orgIds.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
                """
                SELECT id, name
                FROM public.sys_org
                WHERE id IN (:orgIds)
                """,
                new MapSqlParameterSource("orgIds", orgIds)
        );
        return rows.stream()
                .filter(row -> row.get("id") instanceof Number)
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("id")).longValue(),
                        row -> row.get("name") == null ? null : String.valueOf(row.get("name")),
                        (existing, replacement) -> existing,
                        HashMap::new
                ));
    }

    private PlanMetrics loadPlanMetrics(Collection<Plan> plans) {
        if (plans == null || plans.isEmpty()) {
            return PlanMetrics.empty();
        }

        List<Long> planIds = plans.stream()
                .map(Plan::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (planIds.isEmpty()) {
            return PlanMetrics.empty();
        }

        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
                """
                SELECT t.plan_id AS plan_id,
                       COUNT(DISTINCT i.id) AS indicator_count,
                       COUNT(DISTINCT m.id) AS milestone_count,
                       COUNT(DISTINCT CASE
                           WHEN UPPER(COALESCE(m.status, '')) = 'COMPLETED' THEN m.id
                       END) AS completed_milestone_count
                FROM public.sys_task t
                LEFT JOIN public.indicator i
                  ON i.task_id = t.task_id
                 AND COALESCE(i.is_deleted, false) = false
                LEFT JOIN public.indicator_milestone m
                  ON m.indicator_id = i.id
                WHERE t.plan_id IN (:planIds)
                  AND COALESCE(t.is_deleted, false) = false
                GROUP BY t.plan_id
                """,
                new MapSqlParameterSource("planIds", planIds)
        );

        Map<Long, Integer> indicatorCounts = new HashMap<>();
        Map<Long, Integer> milestoneCounts = new HashMap<>();
        Map<Long, Integer> completionPercentages = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long planId = asLong(row.get("plan_id"));
            if (planId == null) {
                continue;
            }
            int indicatorCount = asInt(row.get("indicator_count"));
            int milestoneCount = asInt(row.get("milestone_count"));
            int completedMilestoneCount = asInt(row.get("completed_milestone_count"));
            indicatorCounts.put(planId, indicatorCount);
            milestoneCounts.put(planId, milestoneCount);
            completionPercentages.put(planId, calculateCompletionPercentage(milestoneCount, completedMilestoneCount));
        }

        return new PlanMetrics(indicatorCounts, milestoneCounts, completionPercentages);
    }

    private int calculateCompletionPercentage(int milestoneCount, int completedMilestoneCount) {
        if (milestoneCount <= 0) {
            return 0;
        }

        double percentage = (completedMilestoneCount * 100.0) / milestoneCount;
        return (int) Math.round(Math.max(0.0, Math.min(100.0, percentage)));
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            return Long.parseLong(str);
        }
        return null;
    }

    private Map<Long, PlanWorkflowSnapshotQueryService.WorkflowSnapshot> safeLoadWorkflowSnapshotsByPlanIds(List<Long> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return Map.of();
        }
        try {
            return planWorkflowSnapshotQueryService.getWorkflowSnapshotsByPlanIds(planIds);
        } catch (Exception ex) {
            log.warn("Failed to batch load workflow snapshots for plans={}, falling back to base plan responses: {}",
                    planIds.size(), ex.getMessage());
            return Map.of();
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private InternalMilestoneResponse toInternalMilestoneResponse(MilestoneResponse response) {
        if (response == null) {
            return null;
        }
        return InternalMilestoneResponse.builder()
                .id(response.getId())
                .milestoneName(response.getMilestoneName())
                .description(response.getDescription())
                .targetDate(response.getDueDate())
                .status(response.getStatus())
                .completionPercentage(response.getTargetProgress())
                .createTime(response.getCreatedAt())
                .build();
    }

    private void assertNoActivePlanConflict(Long cycleId,
                                            PlanLevel planLevel,
                                            Long createdByOrgId,
                                            Long targetOrgId,
                                            Long currentPlanId) {
        List<Plan> activePlans = planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                cycleId,
                planLevel,
                createdByOrgId,
                targetOrgId
        );
        boolean hasConflict = activePlans.stream()
                .anyMatch(plan -> currentPlanId == null || !currentPlanId.equals(plan.getId()));
        if (hasConflict) {
            throw new ConflictException(String.format(
                    "Plan already exists for cycleId=%s, planLevel=%s, createdByOrgId=%s, targetOrgId=%s",
                    cycleId,
                    planLevel,
                    createdByOrgId,
                    targetOrgId
            ));
        }
    }

    private Plan savePlanHandlingConflict(Plan plan) {
        try {
            return planRepository.save(plan);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(String.format(
                    "Plan already exists for cycleId=%s, planLevel=%s, createdByOrgId=%s, targetOrgId=%s",
                    plan.getCycleId(),
                    plan.getPlanLevel(),
                    plan.getCreatedByOrgId(),
                    plan.getTargetOrgId()
            ));
        }
    }

    private Plan requirePlan(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
    }

    private Cycle requireCycle(Long cycleId) {
        return cycleRepository.findById(cycleId)
                .orElseThrow(() -> new IllegalArgumentException("Cycle not found: " + cycleId));
    }

    /**
     * 将Indicator实体转换为响应DTO
     * 指标状态统一使用 Plan 的状态
     */
    private InternalIndicatorResponse convertIndicatorToResponse(Indicator indicator,
                                                                String planStatus,
                                                                Integer reportProgress,
                                                                CurrentReportContext currentReportContext,
                                                                Map<Long, String> taskNamesById) {
        // 使用 Plan 的状态作为指标状态
        String effectiveStatus = planStatus != null ? planStatus :
                (indicator.getStatus() != null ? indicator.getStatus().name() : "DRAFT");
        PendingIndicatorState pendingIndicatorState = currentReportContext.getPendingState(indicator.getId());
        String ownerOrgName = indicator.getOwnerOrg() != null ? indicator.getOwnerOrg().getName() : null;
        String targetOrgName = indicator.getTargetOrg() != null ? indicator.getTargetOrg().getName() : null;
        String taskName = indicator.getTaskId() == null ? null : taskNamesById.get(indicator.getTaskId());

        return InternalIndicatorResponse.builder()
                .id(indicator.getId())
                .indicatorName(indicator.getName())
                .indicatorCode("IND" + indicator.getId())
                .indicatorDesc(indicator.getDescription())
                .cycleId(indicator.getTaskId()) // 使用taskId作为cycleId（临时方案）
                .ownerOrgId(indicator.getOwnerOrg() != null ? indicator.getOwnerOrg().getId() : null)
                .ownerOrgName(ownerOrgName)
                .ownerDept(ownerOrgName)
                .targetOrgId(indicator.getTargetOrg() != null ? indicator.getTargetOrg().getId() : null)
                .targetOrgName(targetOrgName)
                .responsibleDept(targetOrgName)
                .taskName(taskName)
                .weightPercent(indicator.getWeight())
                .status(effectiveStatus)
                .progress(indicator.getProgress())
                .reportProgress(reportProgress)
                .currentReportId(currentReportContext.currentReportId())
                .progressApprovalStatus(currentReportContext.progressApprovalStatus())
                .pendingProgress(pendingIndicatorState.pendingProgress())
                .pendingRemark(pendingIndicatorState.pendingRemark())
                .pendingAttachments(pendingIndicatorState.pendingAttachments())
                .createdAt(indicator.getCreatedAt())
                .updatedAt(indicator.getUpdatedAt())
                .build();
    }

    /**
     * 将Indicator实体转换为响应DTO（兼容旧方法，用于非Plan关联场景）
     */
    private InternalIndicatorResponse convertIndicatorToResponse(Indicator indicator) {
        return convertIndicatorToResponse(indicator, null, null, CurrentReportContext.empty(), Map.of());
    }

    private Map<Long, String> loadTaskNamesById(List<Indicator> indicators) {
        if (indicators == null || indicators.isEmpty()) {
            return Map.of();
        }

        List<Long> taskIds = indicators.stream()
                .map(Indicator::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }

        return taskIds.stream()
                .map(taskRepository::findById)
                .flatMap(Optional::stream)
                .filter(task -> task.getId() != null)
                .collect(Collectors.toMap(
                        StrategicTask::getId,
                        StrategicTask::getName,
                        (left, right) -> left,
                        HashMap::new
                ));
    }

    private CurrentReportContext getCurrentReportContext(Long planId, List<Long> indicatorIds) {
        if (planId == null) {
            return CurrentReportContext.empty();
        }

        List<Map<String, Object>> reportRows = jdbcTemplate.queryForList(
                """
                SELECT pr.id AS report_id, pr.status AS report_status
                FROM public.plan_report pr
                WHERE pr.plan_id = ?
                  AND pr.is_deleted = false
                  AND pr.status IN ('DRAFT', 'IN_REVIEW', 'REJECTED')
                ORDER BY pr.updated_at DESC NULLS LAST, pr.id DESC
                LIMIT 1
                """,
                planId
        );

        if (reportRows.isEmpty()) {
            return CurrentReportContext.empty();
        }

        Object reportIdValue = reportRows.get(0).get("report_id");
        if (!(reportIdValue instanceof Number reportIdNumber)) {
            return CurrentReportContext.empty();
        }

        Long currentReportId = reportIdNumber.longValue();
        String progressApprovalStatus = mapReportStatusToProgressApprovalStatus(reportRows.get(0).get("report_status"));

        if (indicatorIds == null || indicatorIds.isEmpty()) {
            return new CurrentReportContext(currentReportId, progressApprovalStatus, Map.of());
        }

        List<Map<String, Object>> pendingRows = namedParameterJdbcTemplate.queryForList(
                """
                SELECT pri.id AS plan_report_indicator_id,
                       pri.indicator_id AS indicator_id,
                       pri.progress AS pending_progress,
                       pri.comment AS pending_remark
                FROM public.plan_report_indicator pri
                WHERE pri.report_id = :reportId
                  AND pri.indicator_id IN (:indicatorIds)
                """,
                new MapSqlParameterSource()
                        .addValue("reportId", currentReportId)
                        .addValue("indicatorIds", indicatorIds)
        );

        if (pendingRows.isEmpty()) {
            return new CurrentReportContext(currentReportId, progressApprovalStatus, Map.of());
        }

        Map<Long, Long> reportIndicatorIdByIndicatorId = new HashMap<>();
        Map<Long, PendingIndicatorState> pendingStateByIndicatorId = new HashMap<>();
        for (Map<String, Object> row : pendingRows) {
            Object indicatorIdValue = row.get("indicator_id");
            if (!(indicatorIdValue instanceof Number indicatorIdNumber)) {
                continue;
            }

            Long indicatorId = indicatorIdNumber.longValue();
            Integer pendingProgress = row.get("pending_progress") instanceof Number pendingProgressNumber
                    ? pendingProgressNumber.intValue()
                    : null;
            String pendingRemark = row.get("pending_remark") == null
                    ? null
                    : String.valueOf(row.get("pending_remark"));

            pendingStateByIndicatorId.put(
                    indicatorId,
                    new PendingIndicatorState(pendingProgress, pendingRemark, List.of())
            );

            Object planReportIndicatorIdValue = row.get("plan_report_indicator_id");
            if (planReportIndicatorIdValue instanceof Number planReportIndicatorIdNumber) {
                reportIndicatorIdByIndicatorId.put(indicatorId, planReportIndicatorIdNumber.longValue());
            }
        }

        if (!reportIndicatorIdByIndicatorId.isEmpty()) {
            List<Long> planReportIndicatorIds = new ArrayList<>(reportIndicatorIdByIndicatorId.values());
            List<Map<String, Object>> attachmentRows = namedParameterJdbcTemplate.queryForList(
                    """
                    SELECT pria.plan_report_indicator_id AS plan_report_indicator_id,
                           COALESCE(NULLIF(a.public_url, ''), NULLIF(a.object_key, ''), a.original_name) AS attachment_value
                    FROM public.plan_report_indicator_attachment pria
                    JOIN public.attachment a ON a.id = pria.attachment_id
                    WHERE pria.plan_report_indicator_id IN (:reportIndicatorIds)
                      AND COALESCE(a.is_deleted, false) = false
                    ORDER BY pria.sort_order ASC, pria.id ASC
                    """,
                    new MapSqlParameterSource("reportIndicatorIds", planReportIndicatorIds)
            );

            Map<Long, List<String>> attachmentsByReportIndicatorId = new HashMap<>();
            for (Map<String, Object> row : attachmentRows) {
                Object reportIndicatorIdValue = row.get("plan_report_indicator_id");
                Object attachmentValue = row.get("attachment_value");
                if (!(reportIndicatorIdValue instanceof Number reportIndicatorIdNumber) || attachmentValue == null) {
                    continue;
                }
                String attachment = String.valueOf(attachmentValue).trim();
                if (attachment.isEmpty()) {
                    continue;
                }
                attachmentsByReportIndicatorId
                        .computeIfAbsent(reportIndicatorIdNumber.longValue(), ignored -> new ArrayList<>())
                        .add(attachment);
            }

            for (Map.Entry<Long, Long> entry : reportIndicatorIdByIndicatorId.entrySet()) {
                Long indicatorId = entry.getKey();
                Long planReportIndicatorId = entry.getValue();
                PendingIndicatorState pendingState = pendingStateByIndicatorId.get(indicatorId);
                if (pendingState == null) {
                    continue;
                }
                pendingStateByIndicatorId.put(
                        indicatorId,
                        new PendingIndicatorState(
                                pendingState.pendingProgress(),
                                pendingState.pendingRemark(),
                                attachmentsByReportIndicatorId.getOrDefault(planReportIndicatorId, List.of())
                        )
                );
            }
        }

        return new CurrentReportContext(currentReportId, progressApprovalStatus, pendingStateByIndicatorId);
    }

    private String mapReportStatusToProgressApprovalStatus(Object rawStatus) {
        if (rawStatus == null) {
            return "NONE";
        }

        String normalized = String.valueOf(rawStatus).trim().toUpperCase();
        return switch (normalized) {
            case "DRAFT" -> "DRAFT";
            case "SUBMITTED", "IN_REVIEW" -> "PENDING";
            case "REJECTED" -> "REJECTED";
            default -> "NONE";
        };
    }

    private Map<Long, Integer> getLatestReportProgressByIndicatorIds(List<Long> indicatorIds) {
        if (indicatorIds == null || indicatorIds.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
                """
                SELECT pri.indicator_id AS indicator_id, pri.progress AS report_progress
                FROM public.plan_report_indicator pri
                INNER JOIN (
                    SELECT pri2.indicator_id, MAX(pr.created_at) AS latest_created_at
                    FROM public.plan_report_indicator pri2
                    INNER JOIN public.plan_report pr ON pri2.report_id = pr.id
                    WHERE pr.is_deleted = false
                    AND pri2.indicator_id IN (:indicatorIds)
                    GROUP BY pri2.indicator_id
                ) latest ON latest.indicator_id = pri.indicator_id
                INNER JOIN public.plan_report pr ON pri.report_id = pr.id
                WHERE pr.is_deleted = false
                AND pri.indicator_id IN (:indicatorIds)
                AND pr.created_at = latest.latest_created_at
                """,
                new MapSqlParameterSource("indicatorIds", indicatorIds)
        );

        Map<Long, Integer> reportProgressByIndicatorId = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object indicatorIdValue = row.get("indicator_id");
            Object reportProgressValue = row.get("report_progress");
            if (!(indicatorIdValue instanceof Number indicatorIdNumber)) {
                continue;
            }
            if (!(reportProgressValue instanceof Number reportProgressNumber)) {
                continue;
            }
            reportProgressByIndicatorId.put(indicatorIdNumber.longValue(), reportProgressNumber.intValue());
        }
        return reportProgressByIndicatorId;
    }

    /**
     * 同步指标状态与 Plan 状态
     * 当 Plan 状态变更时，统一更新所有关联指标的状态
     */
    @Transactional
    public void syncIndicatorStatusWithPlan(Plan plan) {
        // 获取 Plan 对应的状态
        IndicatorStatus targetStatus = mapPlanStatusToIndicatorStatus(plan.getStatus());

        List<Indicator> indicators = loadPlanIndicators(plan);

        // 更新所有指标的状态后一次性批量保存，避免逐条写库
        for (Indicator indicator : indicators) {
            indicator.setStatus(targetStatus);
        }
        indicatorRepository.saveAll(indicators);
    }

    /**
     * 将 Plan 状态映射为 Indicator 状态
     */
    private IndicatorStatus mapPlanStatusToIndicatorStatus(String planStatus) {
        return switch (PlanStatus.fromRaw(planStatus)) {
            case DISTRIBUTED -> IndicatorStatus.DISTRIBUTED;
            case PENDING, DRAFT, RETURNED -> IndicatorStatus.DRAFT;
        };
    }

    private List<Indicator> loadPlanIndicators(Plan plan) {
        List<StrategicTask> tasks = taskRepository.findByPlanId(plan.getId());
        List<Long> taskIds = tasks.stream()
                .filter(Objects::nonNull)
                .map(StrategicTask::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));

        if (!taskIds.isEmpty()) {
            List<Indicator> taskBoundIndicators = indicatorRepository.findByTaskIds(taskIds);
            if (!taskBoundIndicators.isEmpty()) {
                return taskBoundIndicators;
            }
        }

        if (plan.getPlanLevel() != PlanLevel.FUNC_TO_COLLEGE) {
            return List.of();
        }

        Long ownerOrgId = plan.getCreatedByOrgId();
        Long targetOrgId = plan.getTargetOrgId();
        if (ownerOrgId == null || targetOrgId == null) {
            return List.of();
        }

        Long cycleId = plan.getCycleId();
        List<Indicator> fallbackIndicators = indicatorRepository.findByOwnerOrgIdAndTargetOrgId(ownerOrgId, targetOrgId);
        if (fallbackIndicators.isEmpty()) {
            return List.of();
        }

        List<Long> fallbackTaskIds = fallbackIndicators.stream()
                .map(Indicator::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        if (fallbackTaskIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> taskIdToCycle = taskRepository.findAllById(fallbackTaskIds).stream()
                .filter(task -> task != null && task.getId() != null)
                .collect(Collectors.toMap(
                        StrategicTask::getId,
                        StrategicTask::getCycleId,
                        (existing, ignored) -> existing
                ));

        return fallbackIndicators.stream()
                .filter(indicator -> {
                    Long taskId = indicator.getTaskId();
                    if (taskId == null) {
                        return false;
                    }
                    Long taskCycleId = taskIdToCycle.get(taskId);
                    return Objects.equals(taskCycleId, cycleId);
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 计划详情响应DTO
     */
    @lombok.Data
    public static class PlanDetailsResponse extends PlanResponse {
        private List<InternalIndicatorResponse> indicators;
        private List<InternalMilestoneResponse> milestones;
        private List<PlanWorkflowSnapshotQueryService.WorkflowHistoryItem> workflowHistory;
    }

    /**
     * 指标响应DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class InternalIndicatorResponse {
        private Long id;
        private String indicatorName;
        private String indicatorCode;
        private String indicatorDesc;
        private Long cycleId;
        private Long ownerOrgId;
        private String ownerOrgName;
        private String ownerDept;
        private Long targetOrgId;
        private String targetOrgName;
        private String responsibleDept;
        private String taskName;
        private java.math.BigDecimal weightPercent;
        private String status;
        private Integer progress;
        private Integer reportProgress;
        private Long currentReportId;
        private String progressApprovalStatus;
        private Integer pendingProgress;
        private String pendingRemark;
        private List<String> pendingAttachments;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime updatedAt;
    }

    private record CurrentReportContext(Long currentReportId,
                                        String progressApprovalStatus,
                                        Map<Long, PendingIndicatorState> pendingStateByIndicatorId) {
        private static CurrentReportContext empty() {
            return new CurrentReportContext(null, "NONE", Map.of());
        }

        private PendingIndicatorState getPendingState(Long indicatorId) {
            if (indicatorId == null) {
                return PendingIndicatorState.empty();
            }
            return pendingStateByIndicatorId.getOrDefault(indicatorId, PendingIndicatorState.empty());
        }
    }

    private record PendingIndicatorState(Integer pendingProgress,
                                         String pendingRemark,
                                         List<String> pendingAttachments) {
        private PendingIndicatorState {
            pendingAttachments = pendingAttachments == null ? Collections.emptyList() : List.copyOf(pendingAttachments);
        }

        private static PendingIndicatorState empty() {
            return new PendingIndicatorState(null, null, List.of());
        }
    }

    /**
     * 里程碑响应DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class InternalMilestoneResponse {
        private Long id;
        private String milestoneName;
        private String description;
        private java.time.LocalDateTime targetDate;
        private String status;
        private Integer priority;
        private Integer completionPercentage;
        private Long planId;
        private java.time.LocalDateTime createTime;
    }
}
