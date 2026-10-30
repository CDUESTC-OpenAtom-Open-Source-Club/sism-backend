package com.sism.main.application;

import com.sism.iam.domain.user.UserRepository;
import com.sism.main.interfaces.dto.BusinessImportDtos.ConflictMode;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportAction;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitRequest;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.application.BasicTaskWeightValidationService;
import com.sism.strategy.application.StrategyApplicationService;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.plan.PlanStatus;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.task.domain.repository.TaskRepository;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.task.TaskType;
import com.sism.workflow.application.WorkflowApplicationService;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Business Import Application Service Tests")
class BusinessImportApplicationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private IndicatorRepository indicatorRepository;
    @Mock
    private StrategyApplicationService strategyApplicationService;
    @Mock
    private BasicTaskWeightValidationService basicTaskWeightValidationService;
    @Mock
    private WorkflowApplicationService workflowApplicationService;
    @Mock
    private com.sism.workflow.application.BusinessWorkflowApplicationService businessWorkflowApplicationService;
    @Mock
    private AuditInstanceRepository auditInstanceRepository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private JdbcTemplate importBatchJdbcTemplate;

    private BusinessImportApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BusinessImportApplicationService(
                new ExcelBusinessImportParser(),
                userRepository,
                organizationRepository,
                planRepository,
                taskRepository,
                indicatorRepository,
                strategyApplicationService,
                basicTaskWeightValidationService,
                workflowApplicationService,
                businessWorkflowApplicationService,
                auditInstanceRepository,
                transactionTemplate,
                importBatchJdbcTemplate);
    }

    @Test
    @DisplayName("Should fail auto approval on commit when persisted basic weights do not equal 100")
    void shouldFailAutoApprovalOnCommitWhenPersistedBasicWeightsDoNotEqual100() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);
        doThrow(new IllegalStateException("学院指标权重合计必须为100，当前为70"))
                .when(basicTaskWeightValidationService)
                .validatePlanBasicWeight(900L, fixture.college().getId());

        var preview = service.previewDistribution(
                distributionWorkbook("70-percent-basic-weight.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.APPEND,
                        true,
                        "导入后自动下发审批"),
                fixture.currentUser());

        assertEquals("COMMIT_BLOCKED", result.status());
        assertEquals(0, result.createdCount());
        assertEquals(0, result.updatedCount());
        assertNotNull(result.workflow());
        assertEquals("BLOCKED", result.workflow().status());
        assertEquals("学院指标权重合计必须为100，当前为70", result.workflow().message());
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    @Test
    @DisplayName("Should fail auto approval when functional dept mutation is in review")
    void shouldFailAutoApprovalWhenFunctionalDeptMutationInReview() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);
        // 战略部对该职能部门指标发起的 PLAN_MUTATION_STRATEGY 审批仍在 IN_REVIEW
        when(importBatchJdbcTemplate.queryForObject(
                contains("PLAN_MUTATION_STRATEGY"),
                eq(Integer.class),
                eq(fixture.functionalOrg().getId()),
                eq(2026L)))
                .thenReturn(1);

        var preview = service.previewDistribution(
                distributionWorkbook("mutation-in-review.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.APPEND,
                        true,
                        "导入后自动下发审批"),
                fixture.currentUser());

        assertNotNull(result.workflow());
        assertEquals("FAILED", result.workflow().status());
        assertTrue(result.workflow().message().contains("正在异动审批中"));
        // 守卫在启动审批实例之前生效，不应触碰审批流程
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    @Test
    @DisplayName("Should allow normal import when persisted basic weights do not equal 100")
    void shouldAllowNormalImportWhenPersistedBasicWeightsDoNotEqual100() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);

        CurrentUser reporterOnly = reporterOnlyUser(fixture);
        var preview = service.previewDistribution(
                distributionWorkbook("70-percent-basic-weight-normal-import.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                reporterOnly);

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.APPEND,
                        false,
                        "确认导入"),
                reporterOnly);

        assertEquals("COMMITTED", result.status());
        assertEquals(2, result.createdCount());
        assertNotNull(result.workflow());
        assertEquals("导入成功，未自动发起审批", result.workflow().message());
        verify(basicTaskWeightValidationService, never()).validatePlanBasicWeight(anyLong(), anyLong());
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    @Test
    @DisplayName("Should allow reporter-only user after import permission policy change")
    void shouldAllowAnyUserToAutoApproveAfterPolicyChange() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        var preview = service.previewDistribution(
                distributionWorkbook("reporter-auto-approve.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                fixture.currentUser());

        CurrentUser reporterOnly = reporterOnlyUser(fixture);
        stubCommitDependencies(fixture);

        // P6 修订：导入不做权限限制，填报人也可自动审批（系统默认账号留痕）
        assertDoesNotThrow(() -> {
            service.commit(
                    preview.batchId(),
                    new ImportCommitRequest(
                            preview.confirmToken(),
                            ConflictMode.APPEND,
                            true,
                            "导入后自动下发审批"),
                    reporterOnly);
        });
        verify(transactionTemplate).execute(any());
    }

    private CurrentUser reporterOnlyUser(Fixture fixture) {
        return new CurrentUser(
                fixture.currentUser().getId(),
                "hr_report",
                "HR Reporter",
                "hr@example.com",
                fixture.functionalOrg().getId(),
                List.of(new SimpleGrantedAuthority("ROLE_REPORTER")));
    }

    @Test
    @DisplayName("Should block auto approval when imported plan weight is valid but already distributed")
    void shouldBlockAutoApprovalWhenImportedPlanWeightIsValidButAlreadyDistributed() {
        Fixture fixture = fixture();
        Plan distributedPlan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        distributedPlan.setId(900L);
        distributedPlan.setStatus(PlanStatus.DISTRIBUTED.value());
        distributedPlan.setAuditInstanceId(7001L);

        stubPreviewDependencies(fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.functionalOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty(), Optional.of(distributedPlan));

        var preview = service.previewDistribution(
                distributionWorkbook("already-distributed.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.UPDATE,
                        true,
                        "导入后自动下发审批"),
                fixture.currentUser());

        assertEquals("COMMIT_BLOCKED", result.status());
        assertEquals(0, result.createdCount());
        assertEquals(0, result.updatedCount());
        assertNotNull(result.workflow());
        assertEquals("BLOCKED", result.workflow().status());
        assertEquals(7001L, result.workflow().instanceId());
        assertEquals("该部门计划已下发，不能再通过导入修改。如需新增或调整指标，请在表格中双击指标编辑或点「新增行」，改完后点击顶部「发起异动」提交审批", result.workflow().message());
        verify(transactionTemplate, never()).execute(any());
        verify(basicTaskWeightValidationService, never()).validatePlanBasicWeight(anyLong(), anyLong());
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    @Test
    @DisplayName("Should report already distributed before weight validation")
    void shouldReportAlreadyDistributedBeforeWeightValidation() {
        Fixture fixture = fixture();
        Plan distributedPlan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        distributedPlan.setId(900L);
        distributedPlan.setStatus(PlanStatus.DISTRIBUTED.value());
        distributedPlan.setAuditInstanceId(7001L);

        stubPreviewDependencies(fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.functionalOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty(), Optional.of(distributedPlan));

        var preview = service.previewDistribution(
                distributionWorkbook("already-distributed-invalid-weight.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.UPDATE,
                        true,
                        "导入后自动下发审批"),
                fixture.currentUser());

        assertEquals("COMMIT_BLOCKED", result.status());
        assertNotNull(result.workflow());
        assertEquals("BLOCKED", result.workflow().status());
        assertEquals("该部门计划已下发，不能再通过导入修改。如需新增或调整指标，请在表格中双击指标编辑或点「新增行」，改完后点击顶部「发起异动」提交审批", result.workflow().message());
        verify(transactionTemplate, never()).execute(any());
        verify(basicTaskWeightValidationService, never()).validatePlanBasicWeight(anyLong(), anyLong());
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    // ==================== #83 批次二：行为基线（固化现状，重构回归网） ====================

    @Test
    @DisplayName("D4: distribution commit rejects parent indicator from another cycle")
    void shouldRejectCrossYearParentIndicatorOnDistributionCommit() {
        Fixture fixture = fixture();
        StrategicTask task2025 = crossYearTask(fixture);
        Indicator crossYearIndicator = crossYearIndicator(fixture, task2025.getId());
        stubPreviewDependencies(fixture);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> {
            Plan plan = invocation.getArgument(0);
            plan.setId(900L);
            return plan;
        });
        when(indicatorRepository.findById(2001L)).thenReturn(Optional.of(crossYearIndicator));
        when(taskRepository.findById(task2025.getId())).thenReturn(Optional.of(task2025));

        var preview = service.previewDistribution(
                distributionWorkbook("cross-year-distribution.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                fixture.currentUser());

        var blocked = assertThrows(IllegalArgumentException.class, () -> service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.APPEND,
                        false,
                        "确认导入"),
                fixture.currentUser()));

        assertTrue(blocked.getMessage().contains("属于其他考核年度"));
        assertTrue(blocked.getMessage().contains("2001"));
    }

    @Test
    @DisplayName("2026-10-19: sequence map match updates target, out-of-range sequence creates new")
    void shouldApplySequenceMapMatchAndCreateOutOfRange() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture, false);
        // 既有子指标（id=5001，属于本学院范围）
        Indicator existingChild = existingChildIndicator(fixture, 5001L);
        Plan plan = planWithId(900L, fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, fixture.functionalOrg().getId(), fixture.college().getId()))
                .thenReturn(Optional.of(plan));
        when(taskRepository.findByPlanId(900L)).thenReturn(List.of(fixture.childTask()));
        when(indicatorRepository.findByTaskIds(List.of(fixture.childTask().getId())))
                .thenReturn(List.of(existingChild));
        when(indicatorRepository.findById(5001L)).thenReturn(Optional.of(existingChild));
        when(strategyApplicationService.updateIndicator(
                anyLong(), anyString(), any(), any(), any(), any(), anyLong(),
                any(SysOrg.class), any(SysOrg.class)))
                .thenAnswer(invocation -> {
                    Indicator target = existingChild;
                    target.setIndicatorDesc(invocation.getArgument(1));
                    return target;
                });

        var preview = service.previewDistribution(
                distributionWorkbook("sequence-map.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(1, 5001L),
                fixture.currentUser());

        assertEquals(ImportAction.UPDATE, preview.rows().get(0).action());
        assertEquals(ImportAction.CREATE, preview.rows().get(1).action());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertEquals(1, result.updatedCount());
        assertEquals(1, result.createdCount());
        assertEquals(0, result.deletedCount());
    }

    @Test
    @DisplayName("2026-10-19: sequence map target missing flags row error")
    void shouldFlagRowErrorWhenSequenceMapTargetMissing() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        Plan plan = planWithId(900L, fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, fixture.functionalOrg().getId(), fixture.college().getId()))
                .thenReturn(Optional.of(plan));
        when(taskRepository.findByPlanId(900L)).thenReturn(List.of(fixture.childTask()));
        when(indicatorRepository.findByTaskIds(List.of(fixture.childTask().getId())))
                .thenReturn(List.of());

        var preview = service.previewDistribution(
                distributionWorkbook("sequence-map-missing.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(1, 9999L),
                fixture.currentUser());

        assertEquals(ImportAction.ERROR, preview.rows().get(0).action());
        assertTrue(preview.rows().get(0).errors().stream()
                .anyMatch(msg -> msg.contains("已变化")));
        assertTrue(preview.blocking());
    }

    @Test
    @DisplayName("2026-10-19: UPDATE mode soft-deletes existing indicators not covered by file")
    void shouldSoftDeleteUncoveredIndicatorsOnUpdateMode() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture, false);
        Indicator coveredChild = existingChildIndicator(fixture, 5001L);
        Indicator uncoveredChild = existingChildIndicator(fixture, 5002L);
        Plan plan = planWithId(900L, fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, fixture.functionalOrg().getId(), fixture.college().getId()))
                .thenReturn(Optional.of(plan));
        when(taskRepository.findByPlanId(900L)).thenReturn(List.of(fixture.childTask()));
        when(indicatorRepository.findByTaskIds(List.of(fixture.childTask().getId())))
                .thenReturn(List.of(coveredChild, uncoveredChild));
        when(indicatorRepository.findById(5001L)).thenReturn(Optional.of(coveredChild));
        when(strategyApplicationService.updateIndicator(
                anyLong(), anyString(), any(), any(), any(), any(), anyLong(),
                any(SysOrg.class), any(SysOrg.class)))
                .thenReturn(coveredChild);

        var preview = service.previewDistribution(
                distributionWorkbook("update-cover.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(1, 5001L),
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.UPDATE, false, "确认导入（覆盖）"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertEquals(1, result.deletedCount());
        // 未覆盖的 5002 被软删除（打删除标记保存）
        verify(indicatorRepository).save(eq(uncoveredChild));
        assertEquals(Boolean.TRUE, uncoveredChild.getIsDeleted());
        assertEquals(Boolean.FALSE, coveredChild.getIsDeleted());
    }

    private Plan planWithId(Long planId, Fixture fixture) {
        Plan plan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(planId);
        return plan;
    }

    private Indicator existingChildIndicator(Fixture fixture, Long indicatorId) {
        Indicator indicator = Indicator.create(
                "子指标A",
                fixture.functionalOrg(),
                fixture.college(),
                "定量");
        indicator.setId(indicatorId);
        indicator.setTaskId(fixture.childTask().getId());
        indicator.setParentIndicatorId(fixture.parentIndicator().getId());
        indicator.setIsDeleted(false);
        return indicator;
    }

    @Test
    @DisplayName("P6: successful commit persists import_batch audit row")
    void shouldPersistImportBatchAuditRowOnCommit() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);

        var preview = service.previewDistribution(
                distributionWorkbook("audit-success.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.APPEND,
                        false,
                        "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        verify(importBatchJdbcTemplate).update(
                contains("INSERT INTO public.import_batch"),
                eq(preview.batchId()),
                eq("DISTRIBUTION"),
                eq("audit-success.xlsx"),
                eq(8L),
                eq(4L),
                eq(36L),
                eq(2026L),
                eq(2));
    }

    @Test
    @DisplayName("P6: import_batch audit write failure does not block commit")
    void shouldNotBlockCommitWhenImportBatchAuditWriteFails() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);
        doThrow(new RuntimeException("import_batch table unavailable"))
                .when(importBatchJdbcTemplate)
                .update(anyString(), any(Object[].class));

        var preview = service.previewDistribution(
                distributionWorkbook("audit-failure.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                Map.of(),
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.APPEND,
                        false,
                        "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertEquals(2, result.createdCount());
    }

    private StrategicTask crossYearTask(Fixture fixture) {
        StrategicTask task2025 = StrategicTask.create(
                "旧年度任务",
                TaskType.BASIC,
                900L,
                2025L,
                fixture.college(),
                fixture.functionalOrg());
        task2025.setId(1002L);
        return task2025;
    }

    private Indicator crossYearIndicator(Fixture fixture, Long taskId) {
        Indicator indicator = Indicator.create(
                "父级核心指标A",
                fixture.functionalOrg(),
                fixture.functionalOrg(),
                "定量");
        indicator.setId(2001L);
        indicator.setTaskId(taskId);
        return indicator;
    }

    private void stubPreviewDependencies(Fixture fixture) {
        when(organizationRepository.findById(fixture.functionalOrg().getId()))
                .thenReturn(Optional.of(fixture.functionalOrg()));
        when(organizationRepository.findById(fixture.college().getId()))
                .thenReturn(Optional.of(fixture.college()));
        when(indicatorRepository.findByTargetOrgId(fixture.functionalOrg().getId()))
                .thenReturn(List.of(fixture.parentIndicator()));
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.functionalOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty());
    }

    private void stubCommitDependencies(Fixture fixture) {
        stubCommitDependencies(fixture, true);
    }

    private void stubCommitDependencies(Fixture fixture, boolean savesNewPlan) {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        if (savesNewPlan) {
            when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> {
                Plan plan = invocation.getArgument(0);
                plan.setId(900L);
                return plan;
            });
        }
        when(indicatorRepository.findById(fixture.parentIndicator().getId()))
                .thenReturn(Optional.of(fixture.parentIndicator()));
        when(taskRepository.findById(fixture.parentTask().getId()))
                .thenReturn(Optional.of(fixture.parentTask()));
        when(taskRepository.findByPlanIdAndCycleId(900L, 2026L))
                .thenReturn(List.of(fixture.childTask()));

        AtomicLong indicatorIds = new AtomicLong(3000L);
        when(strategyApplicationService.createIndicator(
                anyString(),
                any(SysOrg.class),
                any(SysOrg.class),
                anyLong(),
                anyLong(),
                anyString(),
                any(BigDecimal.class),
                any(),
                any(),
                any()))
                .thenAnswer(invocation -> {
                    Indicator indicator = Indicator.create(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(5));
                    indicator.setId(indicatorIds.incrementAndGet());
                    indicator.setTaskId(invocation.getArgument(3));
                    indicator.setParentIndicatorId(invocation.getArgument(4));
                    indicator.setWeightPercent(invocation.getArgument(6));
                    return indicator;
                });
    }

    private Fixture fixture() {
        SysOrg functionalOrg = SysOrg.create("人力资源部", OrgType.functional);
        functionalOrg.setId(4L);
        SysOrg college = SysOrg.create("马克思主义学院", OrgType.academic);
        college.setId(36L);

        StrategicTask parentTask = StrategicTask.create(
                "基础任务",
                TaskType.BASIC,
                1001L,
                2026L,
                functionalOrg,
                functionalOrg);
        parentTask.setId(1001L);

        Indicator parentIndicator = Indicator.create("父级核心指标A", functionalOrg, functionalOrg, "定量");
        parentIndicator.setId(2001L);
        parentIndicator.setTaskId(parentTask.getId());

        StrategicTask childTask = StrategicTask.create(
                "基础任务",
                TaskType.BASIC,
                900L,
                2026L,
                college,
                functionalOrg);
        childTask.setId(901L);

        // 组合身份（填报+审批）用于既有自动审批路径测试；纯填报人被拒场景见 shouldRejectAutoApproveCommitForReporterOnlyUser
        // 组合身份（填报+分管校领导席位）用于既有自动审批路径测试；
        // 纯填报人被拒场景见 shouldRejectAutoApproveCommitForReporterOnlyUser
        CurrentUser currentUser = new CurrentUser(
                8L,
                "hr_report",
                "HR Reporter",
                "hr@example.com",
                functionalOrg.getId(),
                List.of(
                        new SimpleGrantedAuthority("ROLE_REPORTER"),
                        new SimpleGrantedAuthority("ROLE_VICE_PRESIDENT")));

        return new Fixture(functionalOrg, college, parentTask, parentIndicator, childTask, currentUser);
    }

    private MockMultipartFile distributionWorkbook(String fileName) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("指标下发");
            Row header = sheet.createRow(0);
            // 2026-10-19：序号列必填（1、2、3…），缺失将被解析器拦截
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("学院");
            header.createCell(2).setCellValue("父级核心指标");
            header.createCell(3).setCellValue("子指标名称");
            header.createCell(4).setCellValue("指标类型");
            header.createCell(5).setCellValue("权重");

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("马克思主义学院");
            first.createCell(2).setCellValue("父级核心指标A");
            first.createCell(3).setCellValue("子指标A");
            first.createCell(4).setCellValue("定量");
            first.createCell(5).setCellValue(30);

            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue(2);
            second.createCell(1).setCellValue("马克思主义学院");
            second.createCell(2).setCellValue("父级核心指标A");
            second.createCell(3).setCellValue("子指标B");
            second.createCell(4).setCellValue("定量");
            second.createCell(5).setCellValue(40);

            workbook.write(outputStream);
            return new MockMultipartFile(
                    "file",
                    fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    outputStream.toByteArray());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private record Fixture(
            SysOrg functionalOrg,
            SysOrg college,
            StrategicTask parentTask,
            Indicator parentIndicator,
            StrategicTask childTask,
            CurrentUser currentUser
    ) {
    }
}
