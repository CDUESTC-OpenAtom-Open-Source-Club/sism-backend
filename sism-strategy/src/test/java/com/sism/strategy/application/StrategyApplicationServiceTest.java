package com.sism.strategy.application;

import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import com.sism.shared.infrastructure.event.EventStore;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.plan.PlanStatus;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.task.TaskType;
import com.sism.task.domain.repository.TaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Strategy Application Service Tests")
class StrategyApplicationServiceTest {

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private EventStore eventStore;

    @Mock
    private IndicatorRepository indicatorRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private BasicTaskWeightValidationService basicTaskWeightValidationService;

    @Mock
    private IndicatorMutationService indicatorMutationService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should keep provided task id when creating indicator for functional to college flow")
    void shouldKeepProvidedTaskIdWhenCreatingIndicator() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("发展规划处", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(61L);

        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createIndicator(
                "新增指标",
                ownerOrg,
                targetOrg,
                901L,
                null,
                "定量",
                BigDecimal.valueOf(20),
                1,
                "备注",
                0
        );

        ArgumentCaptor<Indicator> indicatorCaptor = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorRepository).save(indicatorCaptor.capture());
        assertEquals(901L, indicatorCaptor.getValue().getTaskId());
        verify(taskRepository, never()).findByPlanId(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should not remap task id or create plan task during distribution")
    void shouldNotRemapTaskIdOrCreatePlanTaskDuringDistribution() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("发展规划处", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg sourceTargetOrg = SysOrg.create("发展规划处", OrgType.functional);
        sourceTargetOrg.setId(41L);
        SysOrg collegeOrg = SysOrg.create("计算机学院", OrgType.academic);
        collegeOrg.setId(61L);

        Indicator indicator = Indicator.create("待下发指标", ownerOrg, sourceTargetOrg, "定量");
        indicator.setId(301L);
        indicator.setTaskId(902L);

        StrategicTask sourceTask = createTask(902L, 7001L, sourceTargetOrg, ownerOrg);

        when(indicatorRepository.findById(301L)).thenReturn(Optional.of(indicator));
        when(taskRepository.findById(902L)).thenReturn(Optional.of(sourceTask));
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.distributeIndicator(301L, collegeOrg, null);

        ArgumentCaptor<Indicator> indicatorCaptor = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorRepository).save(indicatorCaptor.capture());
        assertEquals(902L, indicatorCaptor.getValue().getTaskId());
        verify(taskRepository, never()).findByPlanId(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw when indicator is missing instead of returning null")
    void shouldThrowWhenIndicatorMissing() {
        StrategyApplicationService service = createService();

        when(indicatorRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getIndicatorById(999L));
    }

    @Test
    @DisplayName("Should allow creating indicator even when task plan is distributed (2026-10-20: 新增走异动链)")
    void shouldAllowCreatingIndicatorWhenTaskPlanDistributed() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("发展规划处", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(61L);
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 2026-10-20 用户定案：已下发计划允许战略部新增指标（走异动审批链），不再抛异常
        Indicator created = service.createIndicator(
                        "复制指标",
                        ownerOrg,
                        targetOrg,
                        903L,
                        null,
                        "定量",
                        BigDecimal.valueOf(20),
                        1,
                        "备注",
                        0
                );

        assertNotNull(created);
        verify(indicatorRepository).save(any(Indicator.class));
    }

    @Test
    @DisplayName("Should create child indicator in downstream draft plan when parent task plan is distributed")
    void shouldCreateChildIndicatorInDownstreamDraftPlanWhenParentTaskPlanDistributed() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("党委学工部", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg functionalOrg = SysOrg.create("党委学工部", OrgType.functional);
        functionalOrg.setId(41L);
        SysOrg collegeOrg = SysOrg.create("马克思主义学院", OrgType.academic);
        collegeOrg.setId(61L);

        StrategicTask parentTask = createTask(903L, 7002L, functionalOrg, ownerOrg);
        Plan distributedParentPlan = createPlan(7002L, PlanStatus.DISTRIBUTED);
        Indicator parentIndicator = Indicator.create("推进心理健康教育与危机干预机制建设", ownerOrg, functionalOrg, "定性");
        parentIndicator.setId(501L);
        parentIndicator.setTaskId(903L);

        when(indicatorRepository.findById(501L)).thenReturn(Optional.of(parentIndicator));
        when(taskRepository.findById(903L)).thenReturn(Optional.of(parentTask));
        when(planRepository.findById(7002L)).thenReturn(Optional.of(distributedParentPlan));
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                41L,
                61L
        )).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> {
            Plan saved = invocation.getArgument(0);
            saved.setId(8001L);
            return saved;
        });
        when(taskRepository.findByPlanIdAndCycleId(8001L, 2026L)).thenReturn(List.of());
        when(taskRepository.save(any(StrategicTask.class))).thenAnswer(invocation -> {
            StrategicTask saved = invocation.getArgument(0);
            saved.setId(9901L);
            return saved;
        });
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createIndicator(
                "完成心理健康教育方案制定",
                ownerOrg,
                collegeOrg,
                903L,
                501L,
                "定性",
                BigDecimal.valueOf(45),
                0,
                "下发至学院",
                0
        );

        ArgumentCaptor<Indicator> indicatorCaptor = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorRepository).save(indicatorCaptor.capture());
        assertEquals(9901L, indicatorCaptor.getValue().getTaskId());
        assertEquals(501L, indicatorCaptor.getValue().getParentIndicator().getId());
        verify(planRepository).save(any(Plan.class));
        verify(taskRepository).save(any(StrategicTask.class));
    }

    @Test
    @DisplayName("Should create child indicator in downstream plan when parent task belongs to upstream draft plan")
    void shouldCreateChildIndicatorInDownstreamPlanWhenParentTaskPlanIsUpstreamDraft() {
        StrategyApplicationService service = createService();

        SysOrg functionalOrg = SysOrg.create("教务处", OrgType.functional);
        functionalOrg.setId(44L);
        SysOrg collegeOrg = SysOrg.create("计算机学院", OrgType.academic);
        collegeOrg.setId(57L);

        // 父任务属于上游"战略部→职能部门"计划，且该计划仍是草稿（未下发）
        StrategicTask upstreamTask = createTask(41022L, 4044L, functionalOrg, functionalOrg);
        Plan upstreamDraftPlan = createPlan(4044L, PlanStatus.DRAFT, PlanLevel.STRAT_TO_FUNC, 44L, 35L);
        Indicator parentIndicator = Indicator.create("牵头组织全国大学生学科竞赛报名及指导", functionalOrg, functionalOrg, "定量");
        parentIndicator.setId(2042L);
        parentIndicator.setTaskId(41022L);

        when(indicatorRepository.findById(2042L)).thenReturn(Optional.of(parentIndicator));
        when(taskRepository.findById(41022L)).thenReturn(Optional.of(upstreamTask));
        when(planRepository.findById(4044L)).thenReturn(Optional.of(upstreamDraftPlan));
        // 下游"职能部门→学院"计划尚不存在，应被创建
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                44L,
                57L
        )).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> {
            Plan saved = invocation.getArgument(0);
            saved.setId(404457L);
            return saved;
        });
        when(taskRepository.findByPlanIdAndCycleId(404457L, 2026L)).thenReturn(List.of());
        when(taskRepository.save(any(StrategicTask.class))).thenAnswer(invocation -> {
            StrategicTask saved = invocation.getArgument(0);
            saved.setId(41023L);
            return saved;
        });
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createIndicator(
                "组织计算机学院学生参加全国大学生学科竞赛报名及指导工作",
                functionalOrg,
                collegeOrg,
                41022L,
                2042L,
                "定量",
                BigDecimal.valueOf(100),
                0,
                "教务处下发复测",
                0
        );

        ArgumentCaptor<Indicator> indicatorCaptor = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorRepository).save(indicatorCaptor.capture());
        // 关键断言：子指标必须挂到下游计划新建的任务上，而不是沿用上游任务的 taskId
        assertEquals(41023L, indicatorCaptor.getValue().getTaskId());
        verify(planRepository).save(any(Plan.class));
        verify(taskRepository).save(any(StrategicTask.class));
    }

    @Test
    @DisplayName("Should reuse task id when requested task already belongs to the downstream plan")
    void shouldReuseTaskIdWhenTaskAlreadyBelongsToDownstreamPlan() {
        StrategyApplicationService service = createService();

        SysOrg functionalOrg = SysOrg.create("教务处", OrgType.functional);
        functionalOrg.setId(44L);
        SysOrg collegeOrg = SysOrg.create("计算机学院", OrgType.academic);
        collegeOrg.setId(57L);

        // 请求的 task 已属于下游计划（同 FUNC_TO_COLLEGE 计划），且该计划未下发 -> 沿用
        StrategicTask downstreamTask = createTask(41023L, 404457L, collegeOrg, functionalOrg);
        Plan downstreamPlan = createPlan(404457L, PlanStatus.DRAFT, PlanLevel.FUNC_TO_COLLEGE, 57L, 44L);
        Indicator parentIndicator = Indicator.create("父级核心指标", functionalOrg, functionalOrg, "定量");
        parentIndicator.setId(2042L);
        parentIndicator.setTaskId(41023L);

        when(indicatorRepository.findById(2042L)).thenReturn(Optional.of(parentIndicator));
        when(taskRepository.findById(41023L)).thenReturn(Optional.of(downstreamTask));
        when(planRepository.findById(404457L)).thenReturn(Optional.of(downstreamPlan));
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createIndicator(
                "学院子指标",
                functionalOrg,
                collegeOrg,
                41023L,
                2042L,
                "定量",
                BigDecimal.valueOf(50),
                0,
                "同计划内复用任务",
                0
        );

        ArgumentCaptor<Indicator> indicatorCaptor = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorRepository).save(indicatorCaptor.capture());
        assertEquals(41023L, indicatorCaptor.getValue().getTaskId());
        // 同计划内复用不应新建计划/任务
        verify(planRepository, never()).save(any(Plan.class));
        verify(taskRepository, never()).save(any(StrategicTask.class));
    }

    @Test
    @DisplayName("Should block updating indicator when current task plan is already distributed")
    void shouldAllowUpdatingIndicatorWhenCurrentTaskPlanDistributed() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("发展规划处", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(61L);
        Indicator indicator = Indicator.create("既有指标", ownerOrg, targetOrg, "定量");
        indicator.setId(401L);
        indicator.setTaskId(904L);
        StrategicTask task = createTask(904L, 7003L, targetOrg, ownerOrg);
        Plan plan = createPlan(7003L, PlanStatus.DISTRIBUTED);

        when(indicatorRepository.findById(401L)).thenReturn(Optional.of(indicator));
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 2026-10-20 用户定案：已下发计划允许修改指标（走异动草稿 + 发起异动时校验），
        // 不再在此处抛 DistributedPlanMutationBlockedException
        Indicator updated = service.updateIndicator(
                401L,
                "更新指标",
                BigDecimal.valueOf(30),
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertNotNull(updated);
        verify(indicatorRepository).save(any(Indicator.class));
    }

    @Test
    @DisplayName("Should block deleting indicator when task plan is already distributed")
    void shouldBlockDeletingIndicatorWhenTaskPlanDistributed() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("发展规划处", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(61L);
        Indicator indicator = Indicator.create("待清空指标", ownerOrg, targetOrg, "定量");
        indicator.setId(402L);
        indicator.setTaskId(905L);
        StrategicTask task = createTask(905L, 7004L, targetOrg, ownerOrg);
        Plan plan = createPlan(7004L, PlanStatus.DISTRIBUTED);

        when(indicatorRepository.findById(402L)).thenReturn(Optional.of(indicator));
        when(taskRepository.findById(905L)).thenReturn(Optional.of(task));
        when(planRepository.findById(7004L)).thenReturn(Optional.of(plan));

        DistributedPlanMutationBlockedException exception = assertThrows(
                DistributedPlanMutationBlockedException.class,
                () -> service.deleteIndicator(402L)
        );

        assertEquals("当前任务已下发，不能重复导入或下发", exception.getMessage());
        verify(indicatorRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should allow revoking a never-submitted draft indicator on a distributed plan（取消异动必须能真删）")
    void shouldAllowRevokingNeverSubmittedDraftOnDistributedPlan() {
        StrategyApplicationService service = createService();
        Indicator indicator = buildDraftIndicatorOnDistributedPlan(402L);
        when(indicatorRepository.findById(402L)).thenReturn(Optional.of(indicator));
        Map<String, Object> probe = new HashMap<>();
        probe.put("mutation_status", null);
        probe.put("has_history", false);
        when(jdbcTemplate.queryForList(contains("has_history"), eq(402L))).thenReturn(List.of(probe));

        service.deleteIndicator(402L);

        assertTrue(Boolean.TRUE.equals(indicator.getIsDeleted()));
        verify(indicatorRepository).save(indicator);
    }

    @Test
    @DisplayName("Should still block revoking a draft indicator that already entered the approval chain")
    void shouldStillBlockRevokingDraftIndicatorWithMutationHistory() {
        StrategyApplicationService service = createService();
        Indicator indicator = buildDraftIndicatorOnDistributedPlan(403L);
        when(indicatorRepository.findById(403L)).thenReturn(Optional.of(indicator));
        Map<String, Object> probe = new HashMap<>();
        probe.put("mutation_status", null);
        probe.put("has_history", true);
        when(jdbcTemplate.queryForList(contains("has_history"), eq(403L))).thenReturn(List.of(probe));

        assertThrows(
                DistributedPlanMutationBlockedException.class,
                () -> service.deleteIndicator(403L)
        );
        verify(indicatorRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should still block revoking an indicator that is currently in mutation")
    void shouldStillBlockRevokingIndicatorInMutation() {
        StrategyApplicationService service = createService();
        Indicator indicator = buildDraftIndicatorOnDistributedPlan(404L);
        when(indicatorRepository.findById(404L)).thenReturn(Optional.of(indicator));
        Map<String, Object> probe = new HashMap<>();
        probe.put("mutation_status", "IN_MUTATION");
        probe.put("has_history", false);
        when(jdbcTemplate.queryForList(contains("has_history"), eq(404L))).thenReturn(List.of(probe));

        assertThrows(
                DistributedPlanMutationBlockedException.class,
                () -> service.deleteIndicator(404L)
        );
        verify(indicatorRepository, never()).save(any());
    }

    /** 2026-10-22：已下发计划上的草稿指标（新增行落库后形态），连带守卫所需的 task/plan 打桩 */
    private Indicator buildDraftIndicatorOnDistributedPlan(Long id) {
        SysOrg ownerOrg = SysOrg.create("发展规划处", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(61L);
        Indicator indicator = Indicator.create("刚新增的指标", ownerOrg, targetOrg, "定量");
        indicator.setId(id);
        indicator.setTaskId(905L);
        StrategicTask task = createTask(905L, 7004L, targetOrg, ownerOrg);
        Plan plan = createPlan(7004L, PlanStatus.DISTRIBUTED);
        when(taskRepository.findById(905L)).thenReturn(Optional.of(task));
        when(planRepository.findById(7004L)).thenReturn(Optional.of(plan));
        return indicator;
    }

    @Test
    @DisplayName("D2: 父指标已软删时，其子指标应作为根指标返回（容忍显示）")
    void shouldTreatOrphanChildAsRootWhenParentDeleted() {
        Indicator aliveParent = new Indicator();
        aliveParent.setId(101L);
        aliveParent.setParentIndicatorId(null);
        aliveParent.setIsDeleted(false);
        Indicator deadParent = new Indicator();
        deadParent.setId(990001L);
        deadParent.setParentIndicatorId(null);
        deadParent.setIsDeleted(true);
        Indicator orphanChild = new Indicator();
        orphanChild.setId(990002L);
        orphanChild.setParentIndicatorId(990001L);
        orphanChild.setIsDeleted(false);
        Indicator normalChild = new Indicator();
        normalChild.setId(990003L);
        normalChild.setParentIndicatorId(101L);
        normalChild.setIsDeleted(false);

        when(indicatorRepository.findByTaskId(41001L))
                .thenReturn(List.of(aliveParent, deadParent, orphanChild, normalChild));

        List<Indicator> roots = createService().getRootIndicatorsByTaskId(41001L);

        List<Long> ids = roots.stream().map(Indicator::getId).toList();
        // 存活父 + 软删父 + 其孤儿子 都应作为根返回；父存活的子不提升
        assertTrue(ids.containsAll(List.of(101L, 990001L, 990002L)));
        assertFalse(ids.contains(990003L));
    }

    @Test
    @DisplayName("D10: 已下发计划下新增指标必须保持基础性权重合计=100（合计≠100 时抛异常）")
    void shouldBlockIndicatorCreationWhenDistributedPlanWeightNotHundred() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("纪委办公室", OrgType.functional);
        ownerOrg.setId(37L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(57L);

        StrategicTask task = createTask(41023L, 403757L, targetOrg, ownerOrg);
        Plan distributedPlan = createPlan(403757L, PlanStatus.DISTRIBUTED);

        when(taskRepository.findById(41023L)).thenReturn(Optional.of(task));
        when(planRepository.findById(403757L)).thenReturn(Optional.of(distributedPlan));
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("基础性任务指标权重合计必须为100，当前为110"))
                .when(basicTaskWeightValidationService)
                .validatePlanBasicWeight(403757L, 57L);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.createIndicator(
                        "新增指标",
                        ownerOrg,
                        targetOrg,
                        41023L,
                        null,
                        "定量",
                        BigDecimal.valueOf(10),
                        1,
                        "备注",
                        0
                )
        );

        // 报错文案沿用 BasicTaskWeightValidationService 既有口径
        assertEquals("基础性任务指标权重合计必须为100，当前为110", exception.getMessage());
        // 校验不通过 → 不发布领域事件（等于没落库）
        verify(eventStore, never()).save(any());
    }

    @Test
    @DisplayName("D10: DRAFT 计划下新增指标允许中间态合计≠100（导入批量路径不被拦）")
    void shouldAllowIntermediateWeightWhenPlanDraft() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("纪委办公室", OrgType.functional);
        ownerOrg.setId(37L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(57L);

        StrategicTask task = createTask(41023L, 403757L, targetOrg, ownerOrg);
        Plan draftPlan = createPlan(403757L, PlanStatus.DRAFT);

        when(taskRepository.findById(41023L)).thenReturn(Optional.of(task));
        when(planRepository.findById(403757L)).thenReturn(Optional.of(draftPlan));
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Indicator created = service.createIndicator(
                "新增指标",
                ownerOrg,
                targetOrg,
                41023L,
                null,
                "定量",
                BigDecimal.valueOf(10),
                1,
                "备注",
                0
        );

        assertNotNull(created);
        // DRAFT 计划不触发合计校验（导入是批量逐条建指标，中间态合计必然≠100）
        verify(basicTaskWeightValidationService, never()).validatePlanBasicWeight(any(), any());
    }

    private StrategyApplicationService createService() {
        // 2026-10-20：异动锁移除后 StrategyApplicationService 不再依赖 IndicatorMutationService
        // 2026-10-22：新增 jdbcTemplate 依赖，「可撤销未提交新增」判定要直查异动标记与快照
        return new StrategyApplicationService(
                eventPublisher,
                eventStore,
                indicatorRepository,
                taskRepository,
                planRepository,
                basicTaskWeightValidationService,
                jdbcTemplate
        );
    }

    private StrategicTask createTask(Long taskId, Long planId, SysOrg org, SysOrg createdByOrg) {
        StrategicTask task = StrategicTask.create(
                "既有任务",
                TaskType.BASIC,
                planId,
                2026L,
                org,
                createdByOrg
        );
        task.setId(taskId);
        return task;
    }

    private Plan createPlan(Long planId, PlanStatus status) {
        Plan plan = Plan.create(2026L, 61L, 41L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(planId);
        plan.setStatus(status.value());
        return plan;
    }

    private Plan createPlan(Long planId, PlanStatus status, PlanLevel level, Long targetOrgId, Long createdByOrgId) {
        Plan plan = Plan.create(2026L, targetOrgId, createdByOrgId, level);
        plan.setId(planId);
        plan.setStatus(status.value());
        return plan;
    }
}
