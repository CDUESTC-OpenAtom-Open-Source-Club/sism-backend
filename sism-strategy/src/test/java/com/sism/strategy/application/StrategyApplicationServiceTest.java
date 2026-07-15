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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    @DisplayName("Should keep provided task id when creating indicator for functional to college flow")
    void shouldKeepProvidedTaskIdWhenCreatingIndicator() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("发展规划处", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(61L);

        StrategicTask task = createTask(901L, 7001L, targetOrg, ownerOrg);
        Plan plan = createPlan(7001L, PlanStatus.DRAFT);

        when(taskRepository.findById(901L)).thenReturn(Optional.of(task));
        when(planRepository.findById(7001L)).thenReturn(Optional.of(plan));
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
    @DisplayName("Should block creating indicator when task plan is already distributed")
    void shouldBlockCreatingIndicatorWhenTaskPlanDistributed() {
        StrategyApplicationService service = createService();

        SysOrg ownerOrg = SysOrg.create("发展规划处", OrgType.functional);
        ownerOrg.setId(41L);
        SysOrg targetOrg = SysOrg.create("计算机学院", OrgType.academic);
        targetOrg.setId(61L);
        StrategicTask task = createTask(903L, 7002L, targetOrg, ownerOrg);
        Plan plan = createPlan(7002L, PlanStatus.DISTRIBUTED);

        when(taskRepository.findById(903L)).thenReturn(Optional.of(task));
        when(planRepository.findById(7002L)).thenReturn(Optional.of(plan));

        DistributedPlanMutationBlockedException exception = assertThrows(
                DistributedPlanMutationBlockedException.class,
                () -> service.createIndicator(
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
                )
        );

        assertEquals("当前任务已下发，不能重复导入或下发", exception.getMessage());
        verify(indicatorRepository, never()).save(any());
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
    @DisplayName("Should block updating indicator when current task plan is already distributed")
    void shouldBlockUpdatingIndicatorWhenCurrentTaskPlanDistributed() {
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
        when(taskRepository.findById(904L)).thenReturn(Optional.of(task));
        when(planRepository.findById(7003L)).thenReturn(Optional.of(plan));

        DistributedPlanMutationBlockedException exception = assertThrows(
                DistributedPlanMutationBlockedException.class,
                () -> service.updateIndicator(
                        401L,
                        "更新指标",
                        BigDecimal.valueOf(30),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        assertEquals("当前任务已下发，不能重复导入或下发", exception.getMessage());
        verify(indicatorRepository, never()).save(any());
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

    private StrategyApplicationService createService() {
        return new StrategyApplicationService(
                eventPublisher,
                eventStore,
                indicatorRepository,
                taskRepository,
                planRepository,
                basicTaskWeightValidationService
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
}
