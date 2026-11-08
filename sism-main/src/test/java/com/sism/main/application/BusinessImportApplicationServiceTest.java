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
import com.sism.strategy.application.CollegeDispatchGuardService;
import com.sism.strategy.application.StrategyApplicationService;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.plan.PlanStatus;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    /**
     * 2026-11-05（学院指标直发改造）：学院下发的「异动在途」守卫（显式口径），
     * 与原先内联在本测试里的 SQL 字符串匹配解耦。
     */
    @Mock
    private CollegeDispatchGuardService collegeDispatchGuardService;

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
                importBatchJdbcTemplate,
                new StrategyOrgProperties(),
                collegeDispatchGuardService);
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
                distributionWorkbook("70-percent-basic-weight.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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
        // 2026-11-05（学院指标直发改造）：守卫改为显式口径（父级核心指标的目标部门），
        // 由 CollegeDispatchGuardService 判定，不再内联 SQL。
        when(collegeDispatchGuardService.isSourceOrgMutationInFlight(any(), any()))
                .thenReturn(true);

        var preview = service.previewDistribution(
                distributionWorkbook("mutation-in-review.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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
                distributionWorkbook("70-percent-basic-weight-normal-import.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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
                distributionWorkbook("reporter-auto-approve.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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
                fixture.strategyOrg().getId(),
                List.of(new SimpleGrantedAuthority("ROLE_REPORTER")));
    }

    @Test
    @DisplayName("Should block auto approval when imported plan weight is valid but already distributed")
    void shouldBlockAutoApprovalWhenImportedPlanWeightIsValidButAlreadyDistributed() {
        Fixture fixture = fixture();
        Plan distributedPlan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        distributedPlan.setId(900L);
        distributedPlan.setStatus(PlanStatus.DISTRIBUTED.value());
        distributedPlan.setAuditInstanceId(7001L);

        stubPreviewDependencies(fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.strategyOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty(), Optional.of(distributedPlan));

        var preview = service.previewDistribution(
                distributionWorkbook("already-distributed.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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
                fixture.strategyOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        distributedPlan.setId(900L);
        distributedPlan.setStatus(PlanStatus.DISTRIBUTED.value());
        distributedPlan.setAuditInstanceId(7001L);

        stubPreviewDependencies(fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.strategyOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty(), Optional.of(distributedPlan));

        var preview = service.previewDistribution(
                distributionWorkbook("already-distributed-invalid-weight.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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
    @DisplayName("2026-11-08 单层口径：不再做「父级指标跨年度」校验（父级已不参与导入链路）")
    void shouldNotValidateLegacyParentIndicatorCycle() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);

        // 旧口径：父级指标若属于其他考核年度 → 阻断（要反查父级任务）。
        // 单层直发后学院指标没有父级，这一整类校验连同 findById(parentId) 一起消失。
        var preview = service.previewDistribution(
                distributionWorkbook("legacy-cross-year.xlsx",
                        "马克思主义学院", "基础性", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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
        verify(indicatorRepository, never()).findById(anyLong());
    }

    // ==================== 2026-10-31 层级化定案：按名称匹配更新/新增 ====================

    @Test
    @DisplayName("2026-10-31: same parent + same child name updates, new name creates (name-key matching)")
    void shouldMatchByNameForUpdateAndCreate() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture, false);
        // 既有子指标（id=5001，名称「子指标A」，同父级、本学院范围）
        Indicator existingChild = existingChildIndicator(fixture, 5001L, "子指标A");
        Plan plan = planWithId(900L, fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, fixture.strategyOrg().getId(), fixture.college().getId()))
                .thenReturn(Optional.of(plan));
        when(taskRepository.findByPlanId(900L)).thenReturn(List.of(fixture.childTask()));
        when(indicatorRepository.findByTaskIds(List.of(fixture.childTask().getId())))
                .thenReturn(List.of(existingChild));
        // 提交阶段按（父级+名称）定位更新目标
        when(indicatorRepository.findByTaskId(fixture.childTask().getId()))
                .thenReturn(List.of(existingChild));
        when(strategyApplicationService.updateIndicator(
                anyLong(), anyString(), any(), any(), any(), any(), anyLong(),
                any(SysOrg.class), any(SysOrg.class)))
                .thenAnswer(invocation -> {
                    Indicator target = existingChild;
                    target.setIndicatorDesc(invocation.getArgument(1));
                    return target;
                });

        var preview = service.previewDistribution(
                distributionWorkbook("name-match.xlsx",
                        "马克思主义学院", "基础性", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        // 同名（同父级组内）= 更新；新名称 = 新增
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
    @DisplayName("2026-10-31: update target vanished between preview and commit is skipped safely")
    void shouldSkipRowWhenUpdateTargetVanishedBeforeCommit() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture, false);
        Indicator existingChild = existingChildIndicator(fixture, 5001L, "子指标A");
        Plan plan = planWithId(900L, fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, fixture.strategyOrg().getId(), fixture.college().getId()))
                .thenReturn(Optional.of(plan));
        when(taskRepository.findByPlanId(900L)).thenReturn(List.of(fixture.childTask()));
        when(indicatorRepository.findByTaskIds(List.of(fixture.childTask().getId())))
                .thenReturn(List.of(existingChild));
        // 预览后目标已被删除：提交阶段找不到（父级+名称）匹配 → 安全跳过
        when(indicatorRepository.findByTaskId(fixture.childTask().getId())).thenReturn(List.of());

        var preview = service.previewDistribution(
                distributionWorkbook("target-vanished.xlsx",
                        "马克思主义学院", "基础性", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertEquals(ImportAction.UPDATE, preview.rows().get(0).action());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertEquals(0, result.updatedCount());
        assertEquals(1, result.createdCount());
        assertEquals(1, result.skippedCount());
    }

    @Test
    @DisplayName("2026-10-31: UPDATE mode soft-deletes existing children not covered by file (name-key)")
    void shouldSoftDeleteUncoveredIndicatorsOnUpdateMode() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        // 本用例全部行都是 UPDATE，不触达 createIndicator：transaction/落库依赖单独最小 stub
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        // 2026-11-08（单层口径）：落库链不再反查父级指标/父级任务，这两个 stub 已无意义
        when(taskRepository.findByPlanIdAndCycleId(900L, 2026L))
                .thenReturn(List.of(fixture.childTask()));
        Indicator coveredChild = existingChildIndicator(fixture, 5001L, "子指标A");
        Indicator uncoveredChild = existingChildIndicator(fixture, 5002L, "子指标B");
        Plan plan = planWithId(900L, fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, fixture.strategyOrg().getId(), fixture.college().getId()))
                .thenReturn(Optional.of(plan));
        when(taskRepository.findByPlanId(900L)).thenReturn(List.of(fixture.childTask()));
        when(indicatorRepository.findByTaskIds(List.of(fixture.childTask().getId())))
                .thenReturn(List.of(coveredChild, uncoveredChild));
        when(indicatorRepository.findByTaskId(fixture.childTask().getId()))
                .thenReturn(List.of(coveredChild));
        when(strategyApplicationService.updateIndicator(
                anyLong(), anyString(), any(), any(), any(), any(), anyLong(),
                any(SysOrg.class), any(SysOrg.class)))
                .thenReturn(coveredChild);

        // 文件只含「子指标A」——同父级组内同名更新，未出现的「子指标B」被软删除
        var preview = service.previewDistribution(
                distributionWorkbook("update-cover.xlsx",
                        "马克思主义学院", "基础性", "子指标A", null),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertEquals(ImportAction.UPDATE, preview.rows().get(0).action());

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

    // ==================== 2026-10-31 层级化定案：部门/学院一致性、核心指标类型只读校验 ====================

    @Test
    @DisplayName("2026-10-31: college mismatch with current selection is rejected (blocking error)")
    void shouldRejectRowWhenCollegeMismatch() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        var preview = service.previewDistribution(
                distributionWorkbook("college-mismatch.xlsx",
                        "别的学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertEquals(2, preview.rows().size());
        // 组首行学院填写值与当前选择不一致 → ERROR
        assertEquals(ImportAction.ERROR, preview.rows().get(0).action());
        assertTrue(preview.rows().get(0).errors().stream().anyMatch(msg -> msg.contains("文件中的学院与当前选择不一致")),
                "应提示学院不一致: " + preview.rows().get(0).errors());
        // 延续行学院留空 → 默认当前学院，不受影响
        assertEquals(ImportAction.CREATE, preview.rows().get(1).action());
        assertTrue(preview.rows().get(1).errors().isEmpty());
        assertTrue(preview.blocking());
    }

    @Test
    @DisplayName("2026-10-31: strategic department mismatch with current selection is rejected")
    void shouldRejectRowWhenStrategicDepartmentMismatch() {
        Fixture fixture = fixture();
        when(organizationRepository.findById(fixture.strategyOrg().getId()))
                .thenReturn(Optional.of(fixture.functionalOrg()));

        var preview = service.previewStrategicTasks(
                strategicWorkbook("department-mismatch.xlsx", "人事处"),
                2026L,
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertEquals(1, preview.rows().size());
        var row = preview.rows().get(0);
        assertEquals(ImportAction.ERROR, row.action());
        assertTrue(row.errors().stream().anyMatch(msg -> msg.contains("文件中的职能部门与当前选择不一致")),
                "应提示部门不一致: " + row.errors());
        assertTrue(preview.blocking());
    }

    // ==================== 2026-11-08（学院指标单层口径）：父级锚点/顺序/预填行机制整体移除 ====================

    @Test
    @DisplayName("2026-11-08 单层口径：旧模板「核心指标类型」列不再做一致性校验（不阻断）")
    void shouldIgnoreLegacyParentIndicatorTypeColumn() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        // 旧口径：这一列预填父指标类型，被改 = 锚点不一致 → 阻断。
        // 单层直发后没有父级可对，列即便存在也只是一段不被使用的文本（别名保留仅为旧文件可解析）。
        var preview = service.previewDistribution(
                distributionWorkbook("legacy-parent-type-column.xlsx",
                        "马克思主义学院", "基础性", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking(), "父级类型列已不参与校验，不应阻断");
        assertTrue(preview.rows().get(0).errors().isEmpty(),
                "不应有父级类型错误: " + preview.rows().get(0).errors());
    }

    @Test
    @DisplayName("2026-11-08 单层口径：旧模板「核心指标」列（名称）不再参与匹配，不存在「名称被改」阻断")
    void shouldIgnoreLegacyParentIndicatorNameColumn() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        // 旧口径：父级名被改（系统里不存在）→ 锚点不一致阻断。
        // 单层后父级列不再参与匹配，任意内容都不会再触发该类错误。
        var preview = service.previewDistribution(
                distributionWorkbook("legacy-parent-name.xlsx",
                        "马克思主义学院", "基础性", "子指标A1", "子指标A2"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking(), "父级名称不再参与匹配，不应阻断");
        assertEquals(ImportAction.CREATE, preview.rows().get(0).action());
        assertTrue(preview.rows().get(0).errors().isEmpty(),
                "不应有父级匹配错误: " + preview.rows().get(0).errors());
    }

    @Test
    @DisplayName("2026-11-08 单层口径：行顺序不再受「上级下发顺序」约束")
    void shouldNotValidateRowOrderAgainstParentDispatchOrder() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        // 新模板每一行都是独立的学院指标，顺序只影响排序展示，没有「父级组顺序」概念
        var preview = service.previewDistribution(
                multiRowDistributionWorkbook("row-order-free.xlsx", "马克思主义学院", new String[][]{
                        {"子指标B1", "定量", "60", "基础性"},
                        {"子指标A1", "定量", "40", "基础性"}
                }),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking(), "行顺序不再校验，不应阻断");
        assertEquals(ImportAction.CREATE, preview.rows().get(0).action());
        assertEquals(ImportAction.CREATE, preview.rows().get(1).action());
    }

    @Test
    @DisplayName("2026-11-08 单层口径：文件只填部分指标合法（不再受「父级组子序列」约束）")
    void shouldAllowSubsetOfIndicatorsInFile() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        // 单层口径下没有「系统下发父级组」这个基线，文件想要几条就几条
        var preview = service.previewDistribution(
                multiRowDistributionWorkbook("subset-indicators.xlsx", "马克思主义学院", new String[][]{
                        {"子指标A1", "定量", "100", "基础性"}
                }),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking());
        assertEquals(ImportAction.CREATE, preview.rows().get(0).action());
        assertTrue(preview.rows().get(0).errors().isEmpty());
    }

    @Test
    @DisplayName("2026-11-08 单层口径：指标名称为空的行是漏填，必须阻断（不再有「父级预填行跳过」）")
    void shouldBlockRowWithBlankIndicatorName() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        var preview = service.previewDistribution(
                multiRowDistributionWorkbook("blank-indicator-name.xlsx", "马克思主义学院", new String[][]{
                        {null, "定量", "100", "基础性"}
                }),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertEquals(ImportAction.ERROR, preview.rows().get(0).action());
        assertTrue(preview.rows().get(0).errors().stream()
                        .anyMatch(msg -> msg.contains("指标名称不能为空")),
                "空指标名称应阻断: " + preview.rows().get(0).errors());
        assertTrue(preview.blocking());
    }

    @Test
    @DisplayName("2026-11-08 单层口径：多行多指标按文件顺序落库（不再有父级组）")
    void shouldImportMultipleRowsInFileOrder() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);

        var preview = service.previewDistribution(
                multiRowDistributionWorkbook("multi-row-normal.xlsx", "马克思主义学院", new String[][]{
                        {"子指标A1", "定量", "60", "基础性"},
                        {"子指标B1", "定量", "40", "基础性"}
                }),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking());
        assertEquals(ImportAction.CREATE, preview.rows().get(0).action());
        assertEquals(ImportAction.CREATE, preview.rows().get(1).action());
        assertTrue(preview.rows().stream().allMatch(row -> row.errors().isEmpty()),
                "按文件顺序填写不应有错误: "
                        + preview.rows().stream().flatMap(row -> row.errors().stream()).toList());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertEquals(2, result.createdCount());
    }

    // ==================== 2026-11-08（单层口径）：学院任务类别只由文件「任务类型」列决定 ====================

    @Test
    @DisplayName("2026-11-08 单层口径：学院任务类别取自文件「任务类型」列，不再反查父级任务")
    void shouldUseFileTaskTypeWithoutLookingUpParentTask() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubDistributionCommitBase(fixture);
        // 学院计划内没有任何任务 → findOrCreateTask 走新建分支，新建任务的类别即文件指定值
        when(taskRepository.findByPlanIdAndCycleId(900L, 2026L)).thenReturn(List.of());
        List<StrategicTask> savedTasks = captureCreatedTasks();

        var preview = service.previewDistribution(
                distributionWorkbook("file-task-type-basic.xlsx",
                        "马克思主义学院", "基础性", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertFalse(savedTasks.isEmpty(), "应新建学院任务");
        assertTrue(savedTasks.stream().allMatch(task -> task.getTaskType() == TaskType.BASIC),
                "新建任务类别应取自文件「基础性」: "
                        + savedTasks.stream().map(StrategicTask::getTaskType).toList());
        assertEquals("学院基础性指标", savedTasks.get(0).getName());
        // 单层口径下没有任何「父级任务」查询
        verify(taskRepository, never()).findById(fixture.parentTask().getId());
    }

    @Test
    @DisplayName("2026-11-08 单层口径：文件「任务类型」留空 → 按系统默认发展性落库")
    void shouldFallbackToDevelopmentWhenFileTaskTypeMissing() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubDistributionCommitBase(fixture);
        when(taskRepository.findByPlanIdAndCycleId(900L, 2026L)).thenReturn(List.of());
        List<StrategicTask> savedTasks = captureCreatedTasks();

        // 「任务类型」列留空（旧文件回导的常见形态）：沿用系统默认 DEVELOPMENT
        var preview = service.previewDistribution(
                distributionWorkbook("file-task-type-missing.xlsx",
                        "马克思主义学院", null, "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertFalse(savedTasks.isEmpty(), "应按默认任务类别新建学院任务");
        assertTrue(savedTasks.stream().allMatch(task -> task.getTaskType() == TaskType.DEVELOPMENT),
                "任务类型留空时应按系统默认 DEVELOPMENT 落库: "
                        + savedTasks.stream().map(StrategicTask::getTaskType).toList());
    }

    /** 学院导入提交链路的最小依赖 stub（新建计划 + 父级指标/任务查询 + createIndicator 落库） */
    private void stubDistributionCommitBase(Fixture fixture) {
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
        // 2026-11-08：新口径任务名按类别生成，未命中既有任务时会新建 —— 必须 stub save；
        // 任务命中（同名同类）时不会调用 save，故用 lenient 允许这种「可能用不到」的 stub。
        AtomicLong taskIds = new AtomicLong(9100L);
        lenient().when(taskRepository.save(any(StrategicTask.class))).thenAnswer(invocation -> {
            StrategicTask task = invocation.getArgument(0);
            task.setId(taskIds.incrementAndGet());
            return task;
        });
        AtomicLong indicatorIds = new AtomicLong(3000L);
        // 2026-11-08（学院指标单层口径）：parentIndicatorId 传 null，参数匹配器必须用 any()
        when(strategyApplicationService.createIndicator(
                anyString(),
                any(SysOrg.class),
                any(SysOrg.class),
                anyLong(),
                any(),
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

    /** 捕获 findOrCreateTask 新建的任务（taskRepository.save），用于断言任务类别取值结果 */
    private List<StrategicTask> captureCreatedTasks() {
        List<StrategicTask> savedTasks = new ArrayList<>();
        AtomicLong taskIds = new AtomicLong(9100L);
        when(taskRepository.save(any(StrategicTask.class))).thenAnswer(invocation -> {
            StrategicTask task = invocation.getArgument(0);
            task.setId(taskIds.incrementAndGet());
            savedTasks.add(task);
            return task;
        });
        return savedTasks;
    }

    private Plan planWithId(Long planId, Fixture fixture) {
        Plan plan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(planId);
        return plan;
    }

    private Indicator existingChildIndicator(Fixture fixture, Long indicatorId, String name) {
        // 2026-11-08（学院指标单层口径）：学院指标由战略部直发、不再挂父级，
        // 预览/落库的匹配键因此都是 parent=null，测试数据必须同步（否则永远判 CREATE）。
        Indicator indicator = Indicator.create(
                name,
                fixture.strategyOrg(),
                fixture.college(),
                "定量");
        indicator.setId(indicatorId);
        indicator.setTaskId(fixture.childTask().getId());
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
                distributionWorkbook("audit-success.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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
                // 2026-11-05（学院指标直发改造）：来源组织固定为战略发展部（35）
                eq(35L),
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
                distributionWorkbook("audit-failure.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
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

    // ==================== 2026-11-08（学院指标单层口径）：任务类型列不再与父级比对，直接决定学院任务类别 ====================

    @Test
    @DisplayName("2026-11-08 单层口径：文件「任务类型」不再与父级比对（父级已不存在），预览不阻断")
    void shouldNotBlockWhenFileTaskTypeDiffersFromFormerParentTask() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        // 旧口径要求这一列与系统父级所属任务一致，不一致就阻断；单层后父级已不存在，
        // 这一列变成战略部指定学院任务类别的唯一来源，不做任何比对。
        var preview = service.previewDistribution(
                distributionWorkbook("task-type-column.xlsx",
                        "马克思主义学院", "发展性", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking(),
                "任务类型不再有父级比对，不应阻断: "
                        + preview.rows().stream().flatMap(row -> row.errors().stream()).toList());
        assertEquals(ImportAction.CREATE, preview.rows().get(0).action());
        assertEquals(ImportAction.CREATE, preview.rows().get(1).action());
    }

    @Test
    @DisplayName("2026-11-08 单层口径：文件「任务类型」= 基础性 → 学院任务落 BASIC，发展性 → 落 DEVELOPMENT")
    void shouldCreateCollegeTasksByFileTaskType() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubDistributionCommitBase(fixture);
        when(taskRepository.findByPlanIdAndCycleId(900L, 2026L)).thenReturn(List.of());
        List<StrategicTask> savedTasks = captureCreatedTasks();

        var preview = service.previewDistribution(
                distributionWorkbook("task-type-basic.xlsx",
                        "马克思主义学院", "基础性", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking(), "新模板基础性行不应阻断: "
                + preview.rows().stream().flatMap(row -> row.errors().stream()).toList());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertEquals(2, result.createdCount());
        assertTrue(savedTasks.stream().allMatch(task -> task.getTaskType() == TaskType.BASIC),
                "任务类别应取自文件「任务类型」列: "
                        + savedTasks.stream().map(StrategicTask::getTaskType).toList());
        assertEquals("学院基础性指标", savedTasks.get(0).getName());
    }

    /** 构建学院指标导入工作簿（2026-11-08 新模板 8 列；见 {@link #distributionWorkbook}）。 */

    private StrategicTask crossYearTask(Fixture fixture) {
        StrategicTask task2025 = StrategicTask.create(
                "旧年度任务",
                TaskType.BASIC,
                900L,
                2025L,
                fixture.college(),
                fixture.strategyOrg());
        task2025.setId(1002L);
        return task2025;
    }

    private Indicator crossYearIndicator(Fixture fixture, Long taskId) {
        // 父级核心指标 = 战略部下发给职能部门的核心指标（归属战略部、目标职能部门）
        Indicator indicator = Indicator.create(
                "父级核心指标A",
                fixture.strategyOrg(),
                fixture.functionalOrg(),
                "定量");
        indicator.setId(2001L);
        indicator.setTaskId(taskId);
        return indicator;
    }

    /** 构建父级核心指标（锚点测试用，ID 决定下发顺序基准）：归属战略部、目标职能部门 */
    private Indicator parentIndicator(Fixture fixture, Long id, String name, String type) {
        Indicator indicator = Indicator.create(name, fixture.strategyOrg(), fixture.functionalOrg(), type);
        indicator.setId(id);
        indicator.setTaskId(fixture.parentTask().getId());
        return indicator;
    }

    /**
     * 只 stub 组织查询：存量计划拦截发生在解析工作簿之前，
     * 父级锚点与计划查询根本不会执行，stub 多了会被 Mockito 判为 UnnecessaryStubbing。
     */
    private void stubLegacyPlanOnly(Fixture fixture) {
        when(organizationRepository.findById(fixture.strategyOrg().getId()))
                .thenReturn(Optional.of(fixture.strategyOrg()));
        when(organizationRepository.findById(fixture.college().getId()))
                .thenReturn(Optional.of(fixture.college()));
    }

    /**
     * 2026-11-08（学院指标单层口径）：预览阶段<b>不再</b>查父级锚点
     * （{@code findCoreDispatchAnchors} 已从导入链路移除），因此这里只 stub：
     * 组织查询 + 「该学院本周期是否已有计划」。多 stub 会被 Mockito 判为 UnnecessaryStubbing。
     */
    private void stubPreviewDependencies(Fixture fixture) {
        when(organizationRepository.findById(fixture.strategyOrg().getId()))
                .thenReturn(Optional.of(fixture.strategyOrg()));
        when(organizationRepository.findById(fixture.college().getId()))
                .thenReturn(Optional.of(fixture.college()));
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.strategyOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty());
    }

    // 2026-11-05（R10）：存量「职能部门创建的」学院下发计划存在时，导入必须被明确拒绝。
    //
    // 为什么这是阻塞级问题：findPlanForImportContext 对学院导入固定用战略部 org 去查计划
    // （「已下发不可再导入」的判定键），存量计划的 created_by 是职能部门 → 永远查不中 →
    // 该拦截对存量完全失效；enrichDistributionRows 的「同名即更新」也只在新计划下匹配，
    // 存量同名子指标会被当成新指标再创建一条生效的 → 同学院同周期出现两套生效子指标
    // （口径分裂、权重合计失真）。所以必须在预览入口就挡住并点名历史来源。

    @Test
    @DisplayName("R10：本周期该学院已存在职能部门创建的历史下发计划时，导入必须被明确拒绝")
    void shouldRejectImportWhenLegacyFunctionalDeptPlanExists() {
        Fixture fixture = fixture();

        // 存量计划：创建方是职能部门（改造前的下发方），目标是该学院
        Plan legacyPlan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        legacyPlan.setId(608L);
        legacyPlan.setStatus(PlanStatus.DISTRIBUTED.value());

        stubLegacyPlanOnly(fixture);
        when(planRepository.findByCycleId(2026L)).thenReturn(List.of(legacyPlan));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.previewDistribution(
                        distributionWorkbook("legacy-plan.xlsx",
                                "马克思主义学院", "定量", "子指标A", "子指标B"),
                        2026L,
                        fixture.college().getId(),
                        fixture.strategyOrg().getId(),
                        null,
                        fixture.currentUser())
        );

        assertTrue(error.getMessage().contains("已存在历史下发计划"));
        assertTrue(error.getMessage().contains("只读历史"));
    }

    @Test
    @DisplayName("R10：本周期该学院只有战略部创建的计划时，导入不被存量拦截")
    void shouldNotRejectImportWhenOnlyStrategyPlanExists() {
        Fixture fixture = fixture();

        Plan strategyPlan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        strategyPlan.setId(900L);

        stubPreviewDependencies(fixture);
        when(planRepository.findByCycleId(2026L)).thenReturn(List.of(strategyPlan));

        assertDoesNotThrow(() -> service.previewDistribution(
                distributionWorkbook("strategy-plan.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser()));
    }

    @Test
    @DisplayName("R10：存量计划属于别的学院时，不影响本学院导入")
    void shouldNotRejectImportWhenLegacyPlanTargetsAnotherCollege() {
        Fixture fixture = fixture();

        Plan legacyPlanOfOtherCollege = Plan.create(
                2026L,
                57L,
                fixture.functionalOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        legacyPlanOfOtherCollege.setId(609L);

        stubPreviewDependencies(fixture);
        when(planRepository.findByCycleId(2026L)).thenReturn(List.of(legacyPlanOfOtherCollege));

        assertDoesNotThrow(() -> service.previewDistribution(
                distributionWorkbook("other-college-plan.xlsx",
                        "马克思主义学院", "定量", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser()));
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
        when(taskRepository.findByPlanIdAndCycleId(900L, 2026L))
                .thenReturn(List.of(fixture.childTask()));
        // 2026-11-08：新口径任务名按「学院基础性指标/学院发展性指标」生成，
        // 未命中既有任务时会新建 —— 必须 stub save，否则 findOrCreateTask 返回 null → NPE。
        // 任务命中时不调用 save，因此用 lenient 允许这种「可能用不到」的 stub。
        AtomicLong taskIds = new AtomicLong(9100L);
        lenient().when(taskRepository.save(any(StrategicTask.class))).thenAnswer(invocation -> {
            StrategicTask task = invocation.getArgument(0);
            task.setId(taskIds.incrementAndGet());
            return task;
        });

        AtomicLong indicatorIds = new AtomicLong(3000L);
        // 2026-11-08（学院指标单层口径）：第 5 个参数（parentIndicatorId）现在固定传 null，
        // anyLong() 不匹配 null（会让 stub 落到默认返回值 → NPE），必须用 any()。
        when(strategyApplicationService.createIndicator(
                anyString(),
                any(SysOrg.class),
                any(SysOrg.class),
                anyLong(),
                any(),
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

    @Test
    @DisplayName("2026-11-05（学院指标直发改造）：来源部门不是战略发展部时必须拒绝导入")
    void shouldRejectDistributionPreviewWhenSourceOrgIsNotStrategyDept() {
        Fixture fixture = fixture();
        when(organizationRepository.findById(fixture.functionalOrg().getId()))
                .thenReturn(Optional.of(fixture.functionalOrg()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.previewDistribution(
                        distributionWorkbook("source-not-strategy.xlsx",
                                "马克思主义学院", "定量", "子指标A", "子指标B"),
                        2026L,
                        fixture.college().getId(),
                        fixture.functionalOrg().getId(),
                        null,
                        fixture.currentUser()));

        assertTrue(error.getMessage().contains("职能部门不再向学院分发指标"),
                "应明确告知职能部门不再向学院分发指标: " + error.getMessage());
    }

    @Test
    @DisplayName("2026-11-08（单层口径）：父级锚点匹配已移除——不再出现「同名父级多命中」歧义阻断")
    void shouldNoLongerBlockOnDuplicateLegacyParentNames() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);

        // 原口径：父级按名称反查，同一考核年度出现两条同名核心指标会硬阻断。
        // 单层直发后文件里根本没有父级列、也不做锚点匹配，这条歧义判定随之消失。
        var preview = service.previewDistribution(
                distributionWorkbook("duplicate-legacy-parent.xlsx",
                        "马克思主义学院", "基础性", "子指标A", "子指标B"),
                2026L,
                fixture.college().getId(),
                fixture.strategyOrg().getId(),
                null,
                fixture.currentUser());

        assertFalse(preview.blocking(), "父级不再参与匹配，不应因同名歧义阻断整批导入");
        assertEquals(ImportAction.CREATE, preview.rows().get(0).action());
        assertTrue(preview.rows().get(0).errors().isEmpty(),
                "不应有父级相关错误: " + preview.rows().get(0).errors());
    }

    private Fixture fixture() {
        // 2026-11-05（学院指标直发改造）：学院子指标的下发方 = 战略发展部（org 35）；
        // 职能部门退化为「父级核心指标的下发目标部门」，不再参与下发链路。
        SysOrg strategyOrg = SysOrg.create("战略发展部", OrgType.admin);
        strategyOrg.setId(35L);
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
                strategyOrg);
        parentTask.setId(1001L);

        // 父级核心指标 = 战略部下发给职能部门的核心指标（owner=战略部、target=职能部门、无父级）
        Indicator parentIndicator = Indicator.create("父级核心指标A", strategyOrg, functionalOrg, "定量");
        parentIndicator.setId(2001L);
        parentIndicator.setTaskId(parentTask.getId());

        // 2026-11-08（学院指标单层口径）：学院任务名不再继承父级任务，默认按类别命名，
        // fixture 里这条任务就是「文件填基础性 → 复用同名基础性任务」这条路径的载体。
        StrategicTask childTask = StrategicTask.create(
                "学院基础性指标",
                TaskType.BASIC,
                900L,
                2026L,
                college,
                strategyOrg);
        childTask.setId(901L);

        // 组合身份（填报+审批）用于既有自动审批路径测试；纯填报人被拒场景见 shouldRejectAutoApproveCommitForReporterOnlyUser
        // 组合身份（填报+分管校领导席位）用于既有自动审批路径测试；
        // 纯填报人被拒场景见 shouldRejectAutoApproveCommitForReporterOnlyUser
        CurrentUser currentUser = new CurrentUser(
                8L,
                "hr_report",
                "HR Reporter",
                "hr@example.com",
                strategyOrg.getId(),
                List.of(
                        new SimpleGrantedAuthority("ROLE_REPORTER"),
                        new SimpleGrantedAuthority("ROLE_VICE_PRESIDENT")));

        return new Fixture(functionalOrg, strategyOrg, college, parentTask, parentIndicator, childTask, currentUser);
    }

    /**
     * 构建学院指标导入工作簿（2026-11-08 单层口径，新模板 8 列）：
     * 序号|学院|任务类型|指标名称|指标类型|权重|下发备注|归口职能部门。
     * 指标名 second 传 null 时只生成一行（用于覆盖删除等场景）；
     * functionalOrg 传 null = 该列留空（落库 functional_org_id 为 null，审批席位回退原口径）。
     */
    private MockMultipartFile distributionWorkbook(String fileName,
                                                   String college,
                                                   String taskType,
                                                   String firstChildName,
                                                   String secondChildName) {
        return distributionWorkbook(fileName, college, taskType, firstChildName, secondChildName, null);
    }

    private MockMultipartFile distributionWorkbook(String fileName,
                                                   String college,
                                                   String taskType,
                                                   String firstChildName,
                                                   String secondChildName,
                                                   String functionalOrg) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学院指标模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("学院");
            header.createCell(2).setCellValue("任务类型");
            header.createCell(3).setCellValue("指标名称");
            header.createCell(4).setCellValue("指标类型");
            header.createCell(5).setCellValue("权重（可填 10 / 10% / 0.1）");
            header.createCell(6).setCellValue("下发备注");
            header.createCell(7).setCellValue("归口职能部门");

            appendDistributionRow(sheet, 1, college, taskType, firstChildName, 30, functionalOrg);
            if (secondChildName != null) {
                // 第二行学院留空 = 视为当前选择的学院（学院列留空不参与一致性校验），
                // 与真实模板里「合并单元格从属行读空」同语义
                appendDistributionRow(sheet, 2, null, taskType, secondChildName, 40, functionalOrg);
            }

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

    /**
     * 构建多行学院导入工作簿（新模板 8 列，2026-11-08 单层口径）。
     * row = {指标名称, 指标类型, 权重, 任务类型}，null = 该格留空。
     * 行号即序号（新模板序号=行号，不再有分组语义）。
     */
    private MockMultipartFile multiRowDistributionWorkbook(String fileName,
                                                           String college,
                                                           String[][] rows) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学院指标模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("学院");
            header.createCell(2).setCellValue("任务类型");
            header.createCell(3).setCellValue("指标名称");
            header.createCell(4).setCellValue("指标类型");
            header.createCell(5).setCellValue("权重（可填 10 / 10% / 0.1）");
            header.createCell(6).setCellValue("下发备注");
            header.createCell(7).setCellValue("归口职能部门");

            for (int index = 0; index < rows.length; index++) {
                String[] cells = rows[index];
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(index + 1);
                if (college != null) {
                    row.createCell(1).setCellValue(college);
                }
                if (cells[3] != null) {
                    row.createCell(2).setCellValue(cells[3]);
                }
                if (cells[0] != null) {
                    row.createCell(3).setCellValue(cells[0]);
                }
                if (cells[1] != null) {
                    row.createCell(4).setCellValue(cells[1]);
                }
                if (cells[2] != null) {
                    row.createCell(5).setCellValue(cells[2]);
                }
            }

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

    private void appendDistributionRow(Sheet sheet,
                                       int rowIndex,
                                       String college,
                                       String taskType,
                                       String childName,
                                       int weight,
                                       String functionalOrg) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(rowIndex);
        if (college != null) {
            row.createCell(1).setCellValue(college);
        }
        if (taskType != null) {
            row.createCell(2).setCellValue(taskType);
        }
        row.createCell(3).setCellValue(childName);
        row.createCell(4).setCellValue("定量");
        row.createCell(5).setCellValue(weight);
        if (functionalOrg != null) {
            row.createCell(7).setCellValue(functionalOrg);
        }
    }

    /** 构建职能部门指标导入工作簿（2026-10-31 终版表头） */
    private MockMultipartFile strategicWorkbook(String fileName, String department) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("职能部门指标模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("部门");
            header.createCell(2).setCellValue("战略任务");
            header.createCell(3).setCellValue("任务类型");
            header.createCell(4).setCellValue("核心指标");
            header.createCell(5).setCellValue("指标类型");
            header.createCell(6).setCellValue("权重（可填 10 / 10% / 0.1）");
            header.createCell(7).setCellValue("下发备注");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue(department);
            data.createCell(2).setCellValue("完善校院两级提案办理机制");
            data.createCell(3).setCellValue("发展性");
            data.createCell(4).setCellValue("提案办理满意度");
            data.createCell(5).setCellValue("定量");
            data.createCell(6).setCellValue(10);

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
            SysOrg strategyOrg,
            SysOrg college,
            StrategicTask parentTask,
            Indicator parentIndicator,
            StrategicTask childTask,
            CurrentUser currentUser
    ) {
    }
}
