package com.sism.strategy.application;

import com.sism.exception.ConflictException;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.strategy.domain.plan.event.PlanCreatedEvent;
import com.sism.strategy.domain.plan.event.PlanStatusChangedEvent;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.plan.PlanStatus;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.shared.domain.model.base.DomainEvent;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import com.sism.strategy.domain.cycle.Cycle;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.repository.CycleRepository;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.interfaces.dto.PlanResponse;
import com.sism.strategy.interfaces.dto.CreatePlanRequest;
import com.sism.strategy.interfaces.dto.SubmitPlanApprovalRequest;
import com.sism.strategy.interfaces.dto.UpdatePlanRequest;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.ResultSet;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("Plan Application Service Tests")
class PlanApplicationServiceTest {

    @Mock
    private PlanRepository planRepository;

    @Mock
    private CycleRepository cycleRepository;

    @Mock
    private IndicatorRepository indicatorRepository;

    @Mock
    private BasicTaskWeightValidationService basicTaskWeightValidationService;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private PlanWorkflowSnapshotQueryService planWorkflowSnapshotQueryService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private PlanIntegrityService planIntegrityService;

    private StrategyOrgProperties strategyOrgProperties;

    private PlanApplicationService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(jdbcTemplate.query(
                contains("SELECT role_code"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any()
        )).thenAnswer(invocation -> {
            Object roleId = invocation.getArgument(2);
            if (Long.valueOf(3L).equals(roleId)) {
                return List.of("STRATEGY_DEPT_HEAD");
            }
            if (Long.valueOf(4L).equals(roleId)) {
                return List.of("VICE_PRESIDENT");
            }
            if (Long.valueOf(2L).equals(roleId)) {
                return List.of("APPROVER");
            }
            return List.of();
        });
        lenient().when(jdbcTemplate.query(
                contains("SELECT u.id"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(),
                any()
        )).thenReturn(List.of());
        lenient().when(namedParameterJdbcTemplate.queryForList(
                any(String.class),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of());
        strategyOrgProperties = new StrategyOrgProperties();
        service = new PlanApplicationService(
                planRepository,
                cycleRepository,
                indicatorRepository,
                basicTaskWeightValidationService,
                taskRepository,
                eventPublisher,
                planWorkflowSnapshotQueryService,
                jdbcTemplate,
                namedParameterJdbcTemplate,
                transactionManager,
                planIntegrityService,
                strategyOrgProperties
        );
    }

    @Test
    @DisplayName("Should publish a deterministic plan created event after saving")
    void shouldPublishPlanCreatedEventAfterSaving() {
        Cycle cycle = new Cycle();
        cycle.setId(2026L);
        cycle.setYear(2026);

        CreatePlanRequest request = new CreatePlanRequest();
        request.setCycleId(2026L);
        request.setCreatedByOrgId(35L);
        request.setTargetOrgId(36L);
        request.setPlanType("STRATEGY");

        when(cycleRepository.findById(2026L)).thenReturn(Optional.of(cycle));
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.STRAT_TO_FUNC, 35L, 36L))
                .thenReturn(List.of());
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> {
            Plan saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        PlanResponse response = service.createPlan(request);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertTrue(eventCaptor.getValue() instanceof PlanCreatedEvent);
        PlanCreatedEvent createdEvent = (PlanCreatedEvent) eventCaptor.getValue();
        assertEquals(99L, createdEvent.getPlanId());
        assertEquals("STRAT_TO_FUNC", createdEvent.getPlanLevel());
        assertEquals(36L, createdEvent.getTargetOrgId());
        assertEquals(PlanStatus.DRAFT.value(), response.getStatus());
        verify(planRepository).save(any(Plan.class));
    }

    @Test
    @DisplayName("Should reject duplicate active plan creation before save")
    void shouldRejectDuplicateActivePlanCreationBeforeSave() {
        Cycle cycle = new Cycle();
        cycle.setId(2026L);
        cycle.setYear(2026);

        CreatePlanRequest request = new CreatePlanRequest();
        request.setCycleId(2026L);
        request.setCreatedByOrgId(35L);
        request.setTargetOrgId(36L);
        request.setPlanType("STRATEGY");

        Plan existing = Plan.create(2026L, 36L, 35L, PlanLevel.STRAT_TO_FUNC);
        existing.setId(1L);

        when(cycleRepository.findById(2026L)).thenReturn(Optional.of(cycle));
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.STRAT_TO_FUNC, 35L, 36L))
                .thenReturn(List.of(existing));

        assertThrows(ConflictException.class, () -> service.createPlan(request));
        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject update when business key would collide with another active plan")
    void shouldRejectUpdateWhenBusinessKeyWouldCollide() {
        Plan current = Plan.create(2026L, 37L, 35L, PlanLevel.STRAT_TO_FUNC);
        current.setId(10L);
        Plan existing = Plan.create(2026L, 36L, 35L, PlanLevel.STRAT_TO_FUNC);
        existing.setId(11L);

        UpdatePlanRequest request = new UpdatePlanRequest();
        request.setTargetOrgId(36L);

        when(planRepository.findById(10L)).thenReturn(Optional.of(current));
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.STRAT_TO_FUNC, 35L, 36L))
                .thenReturn(List.of(existing));

        assertThrows(ConflictException.class, () -> service.updatePlan(10L, request));
        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should load plan by task relation instead of treating taskId as planId")
    void shouldLoadPlanByTaskRelation() {
        StrategicTask task = new StrategicTask();
        task.setId(92071L);
        task.setPlanId(1L);

        Plan plan = Plan.create(90L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(1L);

        when(taskRepository.findById(92071L)).thenReturn(Optional.of(task));
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        Optional<com.sism.strategy.interfaces.dto.PlanResponse> result = service.getPlanByTaskId(92071L);

        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
        verify(taskRepository).findById(92071L);
        verify(planRepository).findById(1L);
    }

    @Test
    @DisplayName("Should load only requested organization names for plan responses")
    void shouldLoadOnlyRequestedOrganizationNames() {
        Plan plan = Plan.create(2026L, 36L, 35L, PlanLevel.STRAT_TO_FUNC);
        plan.setId(1L);
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(namedParameterJdbcTemplate.queryForList(
                contains("FROM public.sys_org"),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of(
                Map.of("id", 35L, "name", "战略发展部"),
                Map.of("id", 36L, "name", "党委办公室")
        ));

        PlanResponse response = service.getPlanById(1L).orElseThrow();

        assertEquals("战略发展部", response.getCreatedByOrgName());
        assertEquals("党委办公室", response.getTargetOrgName());
        verify(namedParameterJdbcTemplate).queryForList(
                contains("FROM public.sys_org"),
                any(MapSqlParameterSource.class)
        );
    }

    @Test
    @DisplayName("Should query paged plans from repository instead of loading all plans")
    void shouldUsePagedPlanRepositoryQuery() {
        Cycle cycle = new Cycle();
        cycle.setId(2026L);

        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(1L);

        when(cycleRepository.findByYear(2026)).thenReturn(List.of(cycle));
        when(planRepository.findPage(eq(List.of(2026L)), eq(List.of("DISTRIBUTED", "APPROVED", "ACTIVE", "PUBLISHED")), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(plan), PageRequest.of(0, 20), 1));

        var result = service.getPlans(0, 20, 2026, "DISTRIBUTED");

        assertEquals(1, result.getTotalElements());
        assertEquals(1L, result.getContent().get(0).getId());
        verify(cycleRepository).findByYear(2026);
        verify(planRepository).findPage(eq(List.of(2026L)), eq(List.of("DISTRIBUTED", "APPROVED", "ACTIVE", "PUBLISHED")), any(PageRequest.class));
    }

    @Test
    @DisplayName("Should persist plan as pending before publishing approval workflow event")
    void shouldPersistPlanAsPendingBeforePublishingApprovalWorkflowEvent() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(10L);

        SubmitPlanApprovalRequest request = new SubmitPlanApprovalRequest();
        request.setWorkflowCode("PLAN_DISPATCH_STRATEGY");

        when(planRepository.findById(10L)).thenReturn(Optional.of(plan));
        when(planRepository.save(same(plan))).thenReturn(plan);

        PlanResponse response = service.submitPlanForApproval(10L, request, 188L, 35L);

        assertEquals(PlanStatus.PENDING.value(), plan.getStatus());
        assertEquals(PlanStatus.PENDING.value(), response.getStatus());
        verify(planRepository).save(same(plan));
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, times(2)).publish(eventCaptor.capture());
        List<DomainEvent> events = new ArrayList<>(eventCaptor.getAllValues());
        assertTrue(events.stream().anyMatch(event -> event instanceof PlanStatusChangedEvent));
        assertTrue(events.stream().anyMatch(event -> event instanceof com.sism.strategy.domain.plan.event.PlanSubmittedForApprovalEvent));
    }

    @Test
    @DisplayName("Should allow functional department approval submission from distributed state")
    void shouldAllowFunctionalDepartmentApprovalSubmissionFromDistributedState() {
        Plan plan = Plan.create(2026L, 36L, 35L, PlanLevel.STRAT_TO_FUNC);
        plan.setId(4036L);
        plan.activate();
        plan.clearEvents();

        SubmitPlanApprovalRequest request = new SubmitPlanApprovalRequest();
        request.setWorkflowCode("PLAN_APPROVAL_FUNCDEPT");

        when(planRepository.findById(4036L)).thenReturn(Optional.of(plan));
        when(planRepository.save(same(plan))).thenReturn(plan);

        PlanResponse response = service.submitPlanForApproval(4036L, request, 191L, 36L);

        assertEquals(PlanStatus.PENDING.value(), plan.getStatus());
        assertEquals(PlanStatus.PENDING.value(), response.getStatus());
        verify(planRepository).save(same(plan));
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, times(2)).publish(eventCaptor.capture());
        List<DomainEvent> events = eventCaptor.getAllValues();
        assertTrue(events.stream().anyMatch(event -> event instanceof PlanStatusChangedEvent));
        assertTrue(events.stream().anyMatch(event -> event instanceof com.sism.strategy.domain.plan.event.PlanSubmittedForApprovalEvent));
    }

    @Test
    @DisplayName("Should resume withdrawn workflow step synchronously when resubmitting a withdrawn plan")
    void shouldResumeWithdrawnWorkflowSynchronouslyWhenResubmitting() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(7075L);
        plan.withdraw();
        plan.clearEvents();

        SubmitPlanApprovalRequest request = new SubmitPlanApprovalRequest();
        request.setWorkflowCode("PLAN_DISPATCH_STRATEGY");

        when(planRepository.findById(7075L)).thenReturn(Optional.of(plan));
        when(planRepository.save(same(plan))).thenReturn(plan);
        when(planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(7075L)).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(18L)
                        .workflowStatus("IN_REVIEW")
                        .currentStepName("战略发展部负责人审批")
                        .currentApproverId(189L)
                        .currentApproverName("战略发展部终审人1")
                        .canWithdraw(false)
                        .build()
        ).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(18L)
                        .workflowStatus("IN_REVIEW")
                        .currentStepName("战略发展部负责人审批")
                        .currentApproverId(189L)
                        .currentApproverName("战略发展部终审人1")
                        .canWithdraw(true)
                        .build()
        );
        when(jdbcTemplate.query(
                contains("asi.status = 'WITHDRAWN'"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(18L)
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> rowMapper = (RowMapper<Object>) invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(37L);
            when(rs.getInt(2)).thenReturn(1);
            when(rs.getLong(3)).thenReturn(11L);
            when(rs.getBoolean(4)).thenReturn(true);
            when(rs.getLong(5)).thenReturn(35L);
            return List.of(rowMapper.mapRow(rs, 0));
        });

        PlanResponse response = service.submitPlanForApproval(7075L, request, 188L, 35L);

        assertEquals(PlanStatus.PENDING.value(), plan.getStatus());
        assertEquals(PlanStatus.PENDING.value(), response.getStatus());
        assertEquals(18L, response.getWorkflowInstanceId());
        assertEquals("IN_REVIEW", response.getWorkflowStatus());
        assertEquals("战略发展部负责人审批", response.getCurrentStepName());
        assertTrue(Boolean.TRUE.equals(response.getCanWithdraw()));
        verify(jdbcTemplate).update(
                contains("SET status = 'APPROVED'"),
                eq("系统自动完成提交流程节点"),
                anyLong()
        );
        verify(jdbcTemplate).update(contains("SET status = 'PENDING'"), eq(18L));
        verify(jdbcTemplate).update(contains("UPDATE public.audit_instance"), eq(18L));
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, times(1)).publish(eventCaptor.capture());
        assertTrue(eventCaptor.getValue() instanceof PlanStatusChangedEvent);
    }

    @Test
    @DisplayName("Should resume rejected workflow from withdrawn submit step and append next approval step")
    void shouldResumeRejectedWorkflowFromWithdrawnSubmitStep() {
        Plan plan = Plan.create(2026L, 36L, 35L, PlanLevel.STRAT_TO_FUNC);
        plan.setId(8080L);
        plan.returnForRevision();
        plan.clearEvents();

        SubmitPlanApprovalRequest request = new SubmitPlanApprovalRequest();
        request.setWorkflowCode("PLAN_DISPATCH_FUNCDEPT");

        when(planRepository.findById(8080L)).thenReturn(Optional.of(plan));
        when(planRepository.save(same(plan))).thenReturn(plan);
        when(planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(8080L)).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(28L)
                        .workflowStatus("REJECTED")
                        .currentStepName("填报人提交")
                        .build()
        ).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(28L)
                        .workflowStatus("IN_REVIEW")
                        .currentStepName("职能部门审批人审批")
                        .build()
        );
        when(jdbcTemplate.query(
                contains("asi.status = 'WITHDRAWN'"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(28L)
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> rowMapper = (RowMapper<Object>) invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(81L);
            when(rs.getInt(2)).thenReturn(3);
            when(rs.getLong(3)).thenReturn(4L);
            when(rs.getBoolean(4)).thenReturn(true);
            when(rs.getLong(5)).thenReturn(36L);
            return List.of(rowMapper.mapRow(rs, 0));
        });
        PlanResponse response = service.submitPlanForApproval(8080L, request, 191L, 36L);

        assertEquals(PlanStatus.PENDING.value(), plan.getStatus());
        assertEquals(PlanStatus.PENDING.value(), response.getStatus());
        verify(jdbcTemplate).update(
                contains("SET status = 'APPROVED'"),
                eq("系统自动完成提交流程节点"),
                eq(81L)
        );
        verify(jdbcTemplate).update(contains("INSERT INTO public.audit_step_instance"), eq(4L), eq(28L));
        verify(jdbcTemplate).update(contains("UPDATE public.audit_instance"), eq(28L));
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, times(1)).publish(eventCaptor.capture());
        assertTrue(eventCaptor.getValue() instanceof PlanStatusChangedEvent);
    }

    @Test
    @DisplayName("Should batch workflow snapshot lookup when loading paged plans")
    void shouldBatchWorkflowSnapshotLookupWhenLoadingPagedPlans() {
        Plan first = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        first.setId(1L);
        Plan second = Plan.create(2026L, 36L, 35L, PlanLevel.STRATEGIC);
        second.setId(2L);

        when(planRepository.findPage(eq(List.of()), eq(List.of()), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));
        when(planWorkflowSnapshotQueryService.getWorkflowSnapshotsByPlanIds(List.of(1L, 2L)))
                .thenReturn(Map.of(
                        1L,
                        PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                                .workflowInstanceId(101L)
                                .workflowStatus("IN_REVIEW")
                                .build()
                ));

        var result = service.getPlans(0, 20, null, null);

        assertEquals(2, result.getTotalElements());
        assertEquals(101L, result.getContent().get(0).getWorkflowInstanceId());
        assertEquals("IN_REVIEW", result.getContent().get(0).getWorkflowStatus());
        assertEquals(null, result.getContent().get(1).getWorkflowInstanceId());
        verify(planWorkflowSnapshotQueryService).getWorkflowSnapshotsByPlanIds(List.of(1L, 2L));
        verify(planWorkflowSnapshotQueryService, never()).getWorkflowSnapshotByPlanId(any());
        verify(planRepository, times(1)).findPage(eq(List.of()), eq(List.of()), any(PageRequest.class));
    }

    @Test
    @DisplayName("Should return empty page when year filter has no matching cycles")
    void shouldReturnEmptyPageWhenYearHasNoMatchingCycles() {
        when(cycleRepository.findByYear(2030)).thenReturn(List.of());

        var result = service.getPlans(0, 20, 2030, null);

        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalElements());
        verify(cycleRepository).findByYear(2030);
        verify(planRepository, never()).findPage(any(), any(), any(PageRequest.class));
    }

    @Test
    @DisplayName("Should mark plan as returned when workflow rejects it")
    void shouldMarkPlanAsReturnedWhenWorkflowRejectsIt() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(1L);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        service.markWorkflowRejected(1L, "Need update");

        assertEquals(PlanStatus.RETURNED.value(), plan.getStatus());
        verify(planRepository).save(plan);
    }

    @Test
    @DisplayName("Should mark returned plan back to pending when workflow resumes")
    void shouldMarkReturnedPlanBackToPendingWhenWorkflowResumes() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(12L);
        plan.returnForRevision();

        when(planRepository.findById(12L)).thenReturn(Optional.of(plan));

        service.markWorkflowPending(12L);

        assertEquals(PlanStatus.PENDING.value(), plan.getStatus());
        verify(planRepository).save(plan);
    }

    @Test
    @DisplayName("Should reset plan to draft when workflow is withdrawn before first approval")
    void shouldResetPlanToDraftWhenWorkflowIsWithdrawn() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(11L);
        plan.returnForRevision();

        when(planRepository.findById(11L)).thenReturn(Optional.of(plan));
        when(planRepository.save(same(plan))).thenReturn(plan);
        when(taskRepository.findByPlanId(11L)).thenReturn(List.of());

        service.markWorkflowWithdrawn(11L);

        assertEquals(PlanStatus.DRAFT.value(), plan.getStatus());
        verify(planRepository).save(plan);
    }

    @Test
    @DisplayName("Should withdraw functional-dept approval plan back to draft and keep workflow fields in response")
    void shouldWithdrawFunctionalDeptApprovalPlanBackToDraft() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(21L);
        plan.submitForApproval();

        StrategicTask task = createTask(21001L, 21L);
        Indicator indicator = mock(Indicator.class);

        when(planRepository.findById(21L)).thenReturn(Optional.of(plan));
        when(planRepository.save(same(plan))).thenReturn(plan);
        when(planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(21L)).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(701L)
                        .workflowStatus("IN_REVIEW")
                        .currentStepName("部门审批")
                        .currentApproverId(88L)
                        .currentApproverName("审批人")
                        .canWithdraw(true)
                        .build()
        ).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(701L)
                        .workflowStatus("IN_REVIEW")
                        .canWithdraw(false)
                        .build()
        );
        when(taskRepository.findByPlanId(21L)).thenReturn(List.of(task));
        lenient().when(indicatorRepository.findByTaskIds(List.of(21001L))).thenReturn(List.of(indicator));
        when(jdbcTemplate.query(
                contains("asi.status = 'APPROVED'"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(701L)
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> rowMapper = (RowMapper<Object>) invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(9901L);
            when(rs.getInt(2)).thenReturn(1);
            return List.of(rowMapper.mapRow(rs, 0));
        });
        PlanResponse response = service.withdrawPlan(21L);

        assertEquals(PlanStatus.DRAFT.value(), plan.getStatus());
        assertEquals(PlanStatus.DRAFT.value(), response.getStatus());
        assertEquals("IN_REVIEW", response.getWorkflowStatus());
        assertEquals(701L, response.getWorkflowInstanceId());
        verify(indicator).setStatus(IndicatorStatus.DRAFT);
        verify(indicatorRepository).saveAll(List.of(indicator));
    }

    @Test
    @DisplayName("Should reject withdraw when workflow snapshot is missing or not withdrawable")
    void shouldRejectWithdrawWhenWorkflowIsNotWithdrawable() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(22L);
        plan.submitForApproval();

        when(planRepository.findById(22L)).thenReturn(Optional.of(plan));
        when(planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(22L)).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(702L)
                        .workflowStatus("IN_REVIEW")
                        .canWithdraw(false)
                        .build()
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.withdrawPlan(22L));

        assertEquals("Plan is not in a withdrawable workflow state", error.getMessage());
        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should map returned plan to editable response")
    void shouldMapReturnedPlanToEditableResponse() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(1L);
        plan.returnForRevision();

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(1L)).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(88L)
                        .workflowStatus("IN_REVIEW")
                        .startedAt(java.time.LocalDateTime.now())
                        .lastRejectReason("Need update")
                        .currentStepName("部门审批")
                        .currentApproverId(100L)
                        .currentApproverName("审核人A")
                        .canWithdraw(false)
                        .build()
        );

        PlanResponse response = service.getPlanById(1L).orElseThrow();

        assertEquals(PlanStatus.RETURNED.value(), response.getStatus());
        assertEquals("Need update", response.getLastRejectReason());
        assertTrue(response.getCanEdit());
        assertTrue(response.getCanResubmit());
        assertEquals("IN_REVIEW", response.getWorkflowStatus());
        assertEquals("部门审批", response.getCurrentStepName());
    }

    @Test
    @DisplayName("Should keep workflow-pending plan not editable in response")
    void shouldKeepWorkflowPendingPlanNotEditableInResponse() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(2L);

        when(planRepository.findById(2L)).thenReturn(Optional.of(plan));
        when(planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(2L)).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(98L)
                        .workflowStatus("PENDING")
                        .startedAt(java.time.LocalDateTime.now())
                        .build()
        );

        PlanResponse response = service.getPlanById(2L).orElseThrow();

        assertEquals(PlanStatus.DRAFT.value(), response.getStatus());
        assertEquals("PENDING", response.getWorkflowStatus());
        assertEquals(98L, response.getWorkflowInstanceId());
    }

    @Test
    @DisplayName("Should include workflow snapshot and history in plan details")
    void shouldIncludeWorkflowSnapshotAndHistoryInPlanDetails() {
        Plan plan = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(3L);

        Cycle cycle = new Cycle();
        cycle.setId(2026L);
        cycle.setYear(2026);

        when(planRepository.findById(3L)).thenReturn(Optional.of(plan));
        when(cycleRepository.findById(2026L)).thenReturn(Optional.of(cycle));
        when(taskRepository.findByPlanId(3L)).thenReturn(List.of());
        when(planWorkflowSnapshotQueryService.getWorkflowSnapshotByPlanId(3L)).thenReturn(
                PlanWorkflowSnapshotQueryService.WorkflowSnapshot.builder()
                        .workflowInstanceId(99L)
                        .workflowStatus("IN_REVIEW")
                        .currentStepName("学院审批")
                        .currentApproverId(66L)
                        .currentApproverName("审批人B")
                        .canWithdraw(true)
                        .build()
        );
        when(planWorkflowSnapshotQueryService.getWorkflowHistoryByPlanId(3L)).thenReturn(List.of(
                PlanWorkflowSnapshotQueryService.WorkflowHistoryItem.builder()
                        .taskId(1001L)
                        .stepName("职能部门审批")
                        .operatorId(55L)
                        .operatorName("审批人A")
                        .action("APPROVE")
                        .comment("同意")
                        .build()
        ));

        PlanApplicationService.PlanDetailsResponse response = service.getPlanDetails(3L);

        assertEquals(99L, response.getWorkflowInstanceId());
        assertEquals("IN_REVIEW", response.getWorkflowStatus());
        assertEquals("学院审批", response.getCurrentStepName());
        assertEquals(66L, response.getCurrentApproverId());
        assertEquals("审批人B", response.getCurrentApproverName());
        assertTrue(response.getCanWithdraw());
        assertNotNull(response.getWorkflowHistory());
        assertEquals(1, response.getWorkflowHistory().size());
        assertEquals("职能部门审批", response.getWorkflowHistory().get(0).getStepName());
    }

    @Test
    @DisplayName("Should include latest report progress in plan details indicators")
    void shouldIncludeLatestReportProgressInPlanDetailsIndicators() {
        Plan plan = createPlanWithCycle(4L, 2026L);
        StrategicTask task = createTask(41001L, 4L);
        Indicator indicator = createIndicatorMock(2002L, 41001L, 0, "形成党委统战领域专项推进台账");

        when(planRepository.findById(4L)).thenReturn(Optional.of(plan));
        when(cycleRepository.findById(2026L)).thenReturn(Optional.of(createCycle(2026L, 2026)));
        when(taskRepository.findByPlanId(4L)).thenReturn(List.of(task));
        lenient().when(indicatorRepository.findByTaskIds(List.of(41001L))).thenReturn(List.of(indicator));
        when(planWorkflowSnapshotQueryService.getWorkflowHistoryByPlanId(4L)).thenReturn(List.of());
        lenient().when(namedParameterJdbcTemplate.queryForList(
                contains("FROM public.plan_report_indicator pri"),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of(Map.of("indicator_id", 2002L, "report_progress", 20)));
        when(jdbcTemplate.queryForList(contains("FROM public.plan_report pr"), any(Object[].class)))
                .thenReturn(List.of());

        PlanApplicationService.PlanDetailsResponse response = service.getPlanDetails(4L);

        assertNotNull(response.getIndicators());
        assertEquals(1, response.getIndicators().size());
        assertEquals(20, response.getIndicators().get(0).getReportProgress());
        assertEquals("NONE", response.getIndicators().get(0).getProgressApprovalStatus());
    }

    @Test
    @DisplayName("Should expose current draft pending fields from latest active report")
    void shouldExposeCurrentDraftPendingFieldsFromLatestActiveReport() {
        Plan plan = createPlanWithCycle(5L, 2026L);
        StrategicTask task = createTask(51001L, 5L);
        Indicator indicator = createIndicatorMock(2002L, 51001L, 0, "形成党委统战领域专项推进台账");

        when(planRepository.findById(5L)).thenReturn(Optional.of(plan));
        when(cycleRepository.findById(2026L)).thenReturn(Optional.of(createCycle(2026L, 2026)));
        when(taskRepository.findByPlanId(5L)).thenReturn(List.of(task));
        lenient().when(indicatorRepository.findByTaskIds(List.of(51001L))).thenReturn(List.of(indicator));
        when(planWorkflowSnapshotQueryService.getWorkflowHistoryByPlanId(5L)).thenReturn(List.of());
        lenient().when(namedParameterJdbcTemplate.queryForList(
                contains("FROM public.plan_report_indicator pri"),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of(Map.of(
                "plan_report_indicator_id", 7001L,
                "indicator_id", 2002L,
                "pending_progress", 20,
                "pending_remark", "任务完成"
        )));
        lenient().when(namedParameterJdbcTemplate.queryForList(
                contains("FROM public.plan_report_indicator_attachment pria"),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of(
                Map.of("plan_report_indicator_id", 7001L, "attachment_value", "https://files.example.com/a.pdf"),
                Map.of("plan_report_indicator_id", 7001L, "attachment_value", "推进台账.xlsx")
        ));
        when(jdbcTemplate.queryForList(contains("FROM public.plan_report pr"), any(Object[].class)))
                .thenReturn(List.of(Map.of("report_id", 9001L, "report_status", "DRAFT")));

        PlanApplicationService.PlanDetailsResponse response = service.getPlanDetails(5L);

        assertEquals(1, response.getIndicators().size());
        var indicatorResponse = response.getIndicators().get(0);
        assertEquals(9001L, indicatorResponse.getCurrentReportId());
        assertEquals("DRAFT", indicatorResponse.getProgressApprovalStatus());
        assertEquals(20, indicatorResponse.getPendingProgress());
        assertEquals("任务完成", indicatorResponse.getPendingRemark());
        assertEquals(List.of("https://files.example.com/a.pdf", "推进台账.xlsx"), indicatorResponse.getPendingAttachments());
    }

    @Test
    @DisplayName("Should mark latest rejected active report as rejected even when older draft exists")
    void shouldMarkLatestRejectedActiveReportAsRejected() {
        Plan plan = createPlanWithCycle(6L, 2026L);
        StrategicTask task = createTask(61001L, 6L);
        Indicator indicator = createIndicatorMock(2001L, 61001L, 15, "完成党委办公室年度重点工作分解与落实");

        when(planRepository.findById(6L)).thenReturn(Optional.of(plan));
        when(cycleRepository.findById(2026L)).thenReturn(Optional.of(createCycle(2026L, 2026)));
        when(taskRepository.findByPlanId(6L)).thenReturn(List.of(task));
        lenient().when(indicatorRepository.findByTaskIds(List.of(61001L))).thenReturn(List.of(indicator));
        when(planWorkflowSnapshotQueryService.getWorkflowHistoryByPlanId(6L)).thenReturn(List.of());
        lenient().when(namedParameterJdbcTemplate.queryForList(
                contains("FROM public.plan_report_indicator pri"),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of(Map.of(
                "plan_report_indicator_id", 7101L,
                "indicator_id", 2001L,
                "pending_progress", 18,
                "pending_remark", "退回后待修改"
        )));
        lenient().when(namedParameterJdbcTemplate.queryForList(
                contains("FROM public.plan_report_indicator_attachment pria"),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM public.plan_report pr"), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of("report_id", 9102L, "report_status", "REJECTED"),
                        Map.of("report_id", 9101L, "report_status", "DRAFT")
                ));

        PlanApplicationService.PlanDetailsResponse response = service.getPlanDetails(6L);

        var indicatorResponse = response.getIndicators().get(0);
        assertEquals(9102L, indicatorResponse.getCurrentReportId());
        assertEquals("REJECTED", indicatorResponse.getProgressApprovalStatus());
        assertEquals(18, indicatorResponse.getPendingProgress());
        assertEquals("退回后待修改", indicatorResponse.getPendingRemark());
    }

    @Test
    @DisplayName("Should return empty pending fields when no active report exists")
    void shouldReturnEmptyPendingFieldsWhenNoActiveReportExists() {
        Plan plan = createPlanWithCycle(7L, 2026L);
        StrategicTask task = createTask(71001L, 7L);
        Indicator indicator = createIndicatorMock(2045L, 71001L, 0, "形成党委统战领域专项推进台账");

        when(planRepository.findById(7L)).thenReturn(Optional.of(plan));
        when(cycleRepository.findById(2026L)).thenReturn(Optional.of(createCycle(2026L, 2026)));
        when(taskRepository.findByPlanId(7L)).thenReturn(List.of(task));
        lenient().when(indicatorRepository.findByTaskIds(List.of(71001L))).thenReturn(List.of(indicator));
        when(planWorkflowSnapshotQueryService.getWorkflowHistoryByPlanId(7L)).thenReturn(List.of());

        PlanApplicationService.PlanDetailsResponse response = service.getPlanDetails(7L);

        var indicatorResponse = response.getIndicators().get(0);
        assertEquals("NONE", indicatorResponse.getProgressApprovalStatus());
        assertEquals(null, indicatorResponse.getCurrentReportId());
        assertEquals(null, indicatorResponse.getPendingProgress());
        assertEquals(null, indicatorResponse.getPendingRemark());
        assertEquals(List.of(), indicatorResponse.getPendingAttachments());
    }

    @Test
    @DisplayName("Should batch save indicators when syncing plan status to indicators")
    void shouldBatchSaveIndicatorsWhenSyncingPlanStatus() {
        Plan plan = createPlanWithCycle(8L, 2026L);
        plan.setStatus(PlanStatus.DISTRIBUTED.value());

        StrategicTask task = createTask(81001L, 8L);
        Indicator indicator = new Indicator();
        indicator.setId(9001L);
        indicator.setStatus(IndicatorStatus.DRAFT);

        when(taskRepository.findByPlanId(8L)).thenReturn(List.of(task));
        when(indicatorRepository.findByTaskIds(List.of(81001L))).thenReturn(List.of(indicator));
        when(indicatorRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.syncIndicatorStatusWithPlan(plan);

        assertEquals(IndicatorStatus.DISTRIBUTED, indicator.getStatus());
        verify(indicatorRepository).saveAll(List.of(indicator));
        verify(indicatorRepository, never()).save(any());
    }

    private Plan createPlanWithCycle(Long planId, Long cycleId) {
        Plan plan = Plan.create(cycleId, 35L, 35L, PlanLevel.STRATEGIC);
        plan.setId(planId);
        return plan;
    }

    private Cycle createCycle(Long cycleId, int year) {
        Cycle cycle = new Cycle();
        cycle.setId(cycleId);
        cycle.setYear(year);
        return cycle;
    }

    private StrategicTask createTask(Long taskId, Long planId) {
        StrategicTask task = new StrategicTask();
        task.setId(taskId);
        task.setPlanId(planId);
        return task;
    }

    private Indicator createIndicatorMock(Long indicatorId, Long taskId, int progress, String indicatorName) {
        Indicator indicator = mock(Indicator.class);
        when(indicator.getId()).thenReturn(indicatorId);
        when(indicator.getName()).thenReturn(indicatorName);
        when(indicator.getDescription()).thenReturn(indicatorName);
        when(indicator.getTaskId()).thenReturn(taskId);
        when(indicator.getWeight()).thenReturn(java.math.BigDecimal.valueOf(50));
        when(indicator.getProgress()).thenReturn(progress);
        when(indicator.getCreatedAt()).thenReturn(java.time.LocalDateTime.now());
        when(indicator.getUpdatedAt()).thenReturn(java.time.LocalDateTime.now());
        return indicator;
    }
}
