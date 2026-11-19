package com.sism.main.application;

import com.sism.iam.domain.user.UserRepository;
import com.sism.main.interfaces.dto.BusinessImportDtos.AllImportGroup;
import com.sism.main.interfaces.dto.BusinessImportDtos.AllImportGroupResult;
import com.sism.main.interfaces.dto.BusinessImportDtos.AllImportGroupStatus;
import com.sism.main.interfaces.dto.BusinessImportDtos.AllImportLineStatus;
import com.sism.main.interfaces.dto.BusinessImportDtos.ConflictMode;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportAction;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitRequest;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportRowPreview;
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
import com.sism.strategy.domain.repository.CycleRepository;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import com.sism.task.domain.repository.TaskRepository;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.task.TaskType;
import com.sism.workflow.application.BusinessWorkflowApplicationService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-19（学院「全部导入」）：按「职能部门」列分组拆批的后端单测。
 *
 * <p>覆盖：拆批正确性 / 交叉校验（不符 + 为空兜底）/ 组级权重隔离 / 线状态三分支 /
 * 组间独立事务 / 幂等重传 / confirmToken 篡改阻断。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("学院全部导入（按归口分组拆批）Tests")
class BusinessImportAllDistributionTest {

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
    private BusinessWorkflowApplicationService businessWorkflowApplicationService;
    @Mock
    private AuditInstanceRepository auditInstanceRepository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private JdbcTemplate importBatchJdbcTemplate;
    @Mock
    private CollegeDispatchGuardService collegeDispatchGuardService;
    @Mock
    private CycleRepository cycleRepository;

    private BusinessImportApplicationService service;

    /** 教务处 */
    private SysOrg jiaowu;
    /** 数字校园建设办公室 */
    private SysOrg shuzixiaoyuan;
    private SysOrg strategyOrg;
    private SysOrg college;
    private StrategicTask parentTask;
    private Indicator parentJiaowu;
    private Indicator parentShuzixiaoyuan;
    private CurrentUser currentUser;

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
                collegeDispatchGuardService,
                new com.sism.strategy.application.PlanFunctionalOrgResolver(
                        importBatchJdbcTemplate, planRepository),
                cycleRepository);

        strategyOrg = SysOrg.create("战略发展部", OrgType.admin);
        strategyOrg.setId(35L);
        jiaowu = SysOrg.create("教务处", OrgType.functional);
        jiaowu.setId(4L);
        shuzixiaoyuan = SysOrg.create("数字校园建设办公室", OrgType.functional);
        shuzixiaoyuan.setId(5L);
        college = SysOrg.create("马克思主义学院", OrgType.academic);
        college.setId(36L);

        parentTask = StrategicTask.create("基础任务", TaskType.BASIC, 1001L, 2026L, jiaowu, strategyOrg);
        parentTask.setId(1001L);

        parentJiaowu = Indicator.create("父级核心指标A", strategyOrg, jiaowu, "定量");
        parentJiaowu.setId(2001L);
        parentJiaowu.setTaskId(parentTask.getId());

        parentShuzixiaoyuan = Indicator.create("父级核心指标B", strategyOrg, shuzixiaoyuan, "定量");
        parentShuzixiaoyuan.setId(2002L);
        parentShuzixiaoyuan.setTaskId(parentTask.getId());

        currentUser = new CurrentUser(
                8L, "strategy_report", "战略部填报", "strategy@example.com",
                strategyOrg.getId(),
                List.of(new SimpleGrantedAuthority("ROLE_REPORTER")));
    }

    // ==================== 拆批正确性 ====================

    @Test
    @DisplayName("拆批正确性：双归口文件拆成 2 组，行不串组")
    void shouldSplitRowsIntoTwoFunctionalOrgGroups() {
        stubBaseDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("two-orgs.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "60"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        assertEquals(2, preview.groups().size(), "双归口文件应拆成 2 组");
        AllImportGroup jiaowuGroup = preview.groups().stream()
                .filter(group -> jiaowu.getId().equals(group.functionalOrgId()))
                .findFirst().orElseThrow();
        AllImportGroup otherGroup = preview.groups().stream()
                .filter(group -> shuzixiaoyuan.getId().equals(group.functionalOrgId()))
                .findFirst().orElseThrow();

        assertEquals("教务处", jiaowuGroup.functionalOrgName());
        assertEquals("数字校园建设办公室", otherGroup.functionalOrgName());
        assertEquals(1, jiaowuGroup.rows().size());
        assertEquals(1, otherGroup.rows().size());
        // 行不串组
        assertEquals("子指标A1", jiaowuGroup.rows().get(0).normalized().indicatorName());
        assertEquals("子指标B1", otherGroup.rows().get(0).normalized().indicatorName());
    }

    @Test
    @DisplayName("拆批正确性：同一部门的多个父级组归到同一组（按「职能部门」列而非父级分组）")
    void shouldMergeSameFunctionalOrgRowsIntoOneGroup() {
        stubBaseDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("same-org-two-parents.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "40"},
                        {"2", "教务处", "父级核心指标A", "定量", "子指标A2", "定量", "60"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        assertEquals(1, preview.groups().size(), "同一职能部门的多行应合成 1 组");
        assertEquals(2, preview.groups().get(0).rows().size());
        assertEquals(jiaowu.getId(), preview.groups().get(0).functionalOrgId());
    }

    // ==================== 交叉校验 ====================

    @Test
    @DisplayName("交叉校验：「职能部门」列与父级核心指标归属不符 → 行级错误")
    void shouldRejectRowWhenFunctionalDeptMismatchesParentOwner() {
        stubBaseDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("mismatch.xlsx", new String[][]{
                        {"1", "数字校园建设办公室", "父级核心指标A", "定量", "子指标A1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        assertTrue(preview.blocking(), "归属不符必须阻断");
        assertEquals(0, preview.groups().size(), "归属不符的行不进任何组");
        assertEquals(1, preview.unassignedRows().size());
        assertTrue(preview.unassignedRows().get(0).errors().stream()
                        .anyMatch(msg -> msg.contains("职能部门与核心指标归属不符")),
                "应给出「职能部门与核心指标归属不符」: " + preview.unassignedRows().get(0).errors());
        assertEquals(ImportAction.ERROR, preview.unassignedRows().get(0).action());
    }

    @Test
    @DisplayName("交叉校验：「职能部门」列为空 → 走父级反查兜底，反查不出 → 「无法识别归属部门」")
    void shouldFallbackToParentLookupWhenFunctionalDeptBlank() {
        // 父级匹配不上（系统里没有该核心指标）→ 反查不出 → 无法识别归属部门
        lenient().when(organizationRepository.findById(strategyOrg.getId()))
                .thenReturn(Optional.of(strategyOrg));
        lenient().when(organizationRepository.findById(college.getId()))
                .thenReturn(Optional.of(college));
        lenient().when(organizationRepository.findById(jiaowu.getId()))
                .thenReturn(Optional.of(jiaowu));
        lenient().when(organizationRepository.findByNameContaining("教务处")).thenReturn(List.of(jiaowu));
        when(indicatorRepository.findCoreDispatchAnchors(strategyOrg.getId(), 2026L)).thenReturn(List.of());
        when(indicatorRepository.findCoreIndicatorsInCycle(strategyOrg.getId(), 2026L)).thenReturn(List.of());
        when(planRepository.findByCycleId(2026L)).thenReturn(List.of());

        var preview = service.previewAllDistribution(
                allImportWorkbook("blank-dept.xlsx", new String[][]{
                        {"1", "", "不存在的核心指标", "定量", "子指标A1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        assertTrue(preview.blocking());
        assertEquals(1, preview.unassignedRows().size());
        assertTrue(preview.unassignedRows().get(0).errors().stream()
                        .anyMatch(msg -> msg.contains("无法识别归属部门")),
                "应给出「无法识别归属部门」: " + preview.unassignedRows().get(0).errors());
    }

    @Test
    @DisplayName("交叉校验：「职能部门」列为空但父级可反查 → 归入父级所属部门（兜底成功）")
    void shouldFallbackToParentLookupSuccessfully() {
        stubBaseDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("blank-dept-ok.xlsx", new String[][]{
                        {"1", "", "父级核心指标A", "定量", "子指标A1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        assertEquals(1, preview.groups().size(), "反查成功应归入父级所属部门");
        assertEquals(jiaowu.getId(), preview.groups().get(0).functionalOrgId());
        assertTrue(preview.unassignedRows().isEmpty());
    }

    // ==================== 组级权重隔离 ====================

    @Test
    @DisplayName("组级权重隔离：A 组 100 正常，B 组 60 报错；仅 B 组被拦，A 组不受牵连")
    void shouldIsolateGroupWeightErrors() {
        stubBaseDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("weight-isolation.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "60"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup groupA = preview.groups().stream()
                .filter(group -> jiaowu.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();
        AllImportGroup groupB = preview.groups().stream()
                .filter(group -> shuzixiaoyuan.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();

        assertNull(groupA.groupError(), "A 组权重 100 不应有组级错误");
        assertTrue(groupA.willDispatch(), "A 组应照常下发");
        assertNotNull(groupB.groupError(), "B 组权重 60 必须有组级错误");
        assertFalse(groupB.willDispatch());
        assertTrue(groupB.groupError().contains("数字校园建设办公室"), "组级错误应点名部门: " + groupB.groupError());
        assertTrue(groupB.groupError().contains("60/100"), "组级错误应给出合计: " + groupB.groupError());
    }

    // ==================== 线状态三分支 ====================

    @Test
    @DisplayName("线状态：已下发的线 → 该组「将跳过」+ 原因，其他组照常下发")
    void shouldMarkDistributedLineAsSkipped() {
        stubBaseDependencies();
        Plan distributed = Plan.create(2026L, college.getId(), strategyOrg.getId(),
                PlanLevel.FUNC_TO_COLLEGE, jiaowu.getId());
        distributed.setId(900L);
        distributed.setStatus(PlanStatus.DISTRIBUTED.value());
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, strategyOrg.getId(), college.getId(), jiaowu.getId()))
                .thenReturn(Optional.of(distributed));

        var preview = service.previewAllDistribution(
                allImportWorkbook("distributed-line.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup groupA = preview.groups().stream()
                .filter(group -> jiaowu.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();
        AllImportGroup groupB = preview.groups().stream()
                .filter(group -> shuzixiaoyuan.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();

        assertEquals(AllImportLineStatus.DISTRIBUTED, groupA.lineStatus());
        assertFalse(groupA.willDispatch());
        assertNotNull(groupA.skipReason());
        assertTrue(groupA.skipReason().contains("已下发"), groupA.skipReason());
        // 其他组不受牵连
        assertEquals(AllImportLineStatus.NONE, groupB.lineStatus());
        assertTrue(groupB.willDispatch());
    }

    @Test
    @DisplayName("线状态：在审的线 → 该组归错误类，文案点名部门")
    void shouldBlockGroupWhenLineInReview() {
        stubBaseDependencies();
        Plan inReview = Plan.create(2026L, college.getId(), strategyOrg.getId(),
                PlanLevel.FUNC_TO_COLLEGE, jiaowu.getId());
        inReview.setId(901L);
        inReview.setStatus("IN_REVIEW");
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, strategyOrg.getId(), college.getId(), jiaowu.getId()))
                .thenReturn(Optional.of(inReview));

        var preview = service.previewAllDistribution(
                allImportWorkbook("in-review-line.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup groupA = preview.groups().stream()
                .filter(group -> jiaowu.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();
        assertEquals(AllImportLineStatus.IN_REVIEW, groupA.lineStatus());
        assertFalse(groupA.willDispatch());
        assertNotNull(groupA.groupError());
        assertTrue(groupA.groupError().contains("教务处"), groupA.groupError());
        assertTrue(preview.blocking(), "在审组必须阻断整单提交");
    }

    @Test
    @DisplayName("线状态：无线 / 草稿线 → 将下发")
    void shouldMarkNoneAndDraftLineAsDispatchable() {
        stubBaseDependencies();
        Plan draft = Plan.create(2026L, college.getId(), strategyOrg.getId(),
                PlanLevel.FUNC_TO_COLLEGE, jiaowu.getId());
        draft.setId(902L);
        draft.setStatus(PlanStatus.DRAFT.value());
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, strategyOrg.getId(), college.getId(), jiaowu.getId()))
                .thenReturn(Optional.of(draft));

        var preview = service.previewAllDistribution(
                allImportWorkbook("draft-line.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup group = preview.groups().get(0);
        assertEquals(AllImportLineStatus.DRAFT, group.lineStatus());
        assertTrue(group.willDispatch());
        assertNull(group.skipReason());
    }

    // ==================== 2026-11-19 修正：先判线状态，再判组内权重/行级错误 ====================
    //
    // 旧实现的判定顺序是「先算组级权重/行级错误 → 再判线状态」，导致：
    //   ①「已下发线 + 组内权重≠100」被判成错误组（groupError 非空）→ 前端 blocking 置灰 → 整单无法提交；
    //   ②「已下发线」同时被算进「错误组」与「将跳过」两个计数 → 语义矛盾。
    // 修正后的口径（规划书第二节）：
    //   · 已下发线（DISTRIBUTED）→ 将跳过（黄色），**groupError 恒为 null、不阻断整单**；
    //   · 审批中（PENDING/IN_REVIEW）→ 错误组（红色），阻断整单；
    //   · 无线/草稿线（DRAFT/NONE）→ 再判组内权重≠100 / 行级错误 → 错误组，阻断整单。

    @Test
    @DisplayName("修正：已下发线 + 组内权重=100 → 将跳过、groupError=null、整单不阻断")
    void shouldSkipDistributedLineWithoutGroupError() {
        stubBaseDependencies();
        stubDistributedLine(jiaowu.getId());

        var preview = service.previewAllDistribution(
                allImportWorkbook("distributed-weight-100.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup groupA = preview.groups().stream()
                .filter(group -> jiaowu.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();

        assertEquals(AllImportLineStatus.DISTRIBUTED, groupA.lineStatus());
        assertFalse(groupA.willDispatch());
        assertNotNull(groupA.skipReason());
        assertTrue(groupA.skipReason().contains("已下发"), groupA.skipReason());
        assertNull(groupA.groupError(), "已下发线不产生组级错误");
        assertFalse(preview.blocking(), "已下发线不阻断整单提交");
    }

    @Test
    @DisplayName("修正：已下发线 + 组内权重=60 → 仍是「将跳过」（groupError=null、不阻断），权重提示不拦整单")
    void shouldSkipDistributedLineEvenWhenGroupWeightNotHundred() {
        stubBaseDependencies();
        stubDistributedLine(jiaowu.getId());

        var preview = service.previewAllDistribution(
                allImportWorkbook("distributed-weight-60.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "60"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup groupA = preview.groups().stream()
                .filter(group -> jiaowu.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();
        AllImportGroup groupB = preview.groups().stream()
                .filter(group -> shuzixiaoyuan.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();

        assertEquals(AllImportLineStatus.DISTRIBUTED, groupA.lineStatus());
        assertFalse(groupA.willDispatch());
        assertNotNull(groupA.skipReason());
        assertNull(groupA.groupError(), "已下发线的组内权重不足不产生组级错误: " + groupA.groupError());
        assertEquals(60, groupA.basicWeightTotal().intValue(), "权重合计仍如实展示");
        assertFalse(preview.blocking(), "已下发线不阻断整单提交");

        // 其他组照常下发
        assertTrue(groupB.willDispatch());
        assertNull(groupB.groupError());
    }

    @Test
    @DisplayName("修正：已下发线 + 组内行级错误 → 仍是「将跳过」、不阻断；行级错误在组内行里照常可见")
    void shouldSkipDistributedLineEvenWhenRowHasError() {
        stubBaseDependencies();
        stubDistributedLine(jiaowu.getId());

        // 「职能部门」填了但父级核心指标在系统里不存在 → 行级错误（父级锚点匹配不上），
        // 但因为「职能部门」列显式给了归口，该行仍留在教务处组内。
        var preview = service.previewAllDistribution(
                allImportWorkbook("distributed-row-error.xlsx", new String[][]{
                        {"1", "教务处", "不存在的核心指标", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup groupA = preview.groups().stream()
                .filter(group -> jiaowu.getId().equals(group.functionalOrgId())).findFirst().orElseThrow();

        assertEquals(AllImportLineStatus.DISTRIBUTED, groupA.lineStatus());
        assertFalse(groupA.willDispatch());
        assertNotNull(groupA.skipReason());
        assertNull(groupA.groupError(), "已下发线的行级错误不产生组级错误: " + groupA.groupError());
        assertFalse(preview.blocking(), "已下发线的行级错误不阻断整单提交");

        // 行级错误仍在组内行里照常展示（前端 rows 逐行渲染 errors）
        assertTrue(groupA.rows().stream().anyMatch(ImportRowPreview::hasErrors),
                "行级错误应仍在组内行上可见");
        assertTrue(groupA.rows().stream()
                        .flatMap(row -> row.errors().stream())
                        .anyMatch(msg -> msg.contains("父级核心指标")),
                "行级错误文案应保留: " + groupA.rows().get(0).errors());
    }

    @Test
    @DisplayName("修正：两条线都已下发 → 错误组 0、将跳过 2、整单可提交（不阻断）")
    void shouldNotBlockWhenBothLinesAlreadyDistributed() {
        stubBaseDependencies();
        stubDistributedLine(jiaowu.getId());
        stubDistributedLine(shuzixiaoyuan.getId());

        var preview = service.previewAllDistribution(
                allImportWorkbook("both-distributed.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        assertEquals(2, preview.groups().size());
        assertEquals(0, preview.groups().stream().filter(group -> group.groupError() != null).count(),
                "两组都不该有组级错误");
        assertEquals(2, preview.groups().stream().filter(group -> group.skipReason() != null).count(),
                "两组都该是「将跳过」");
        assertFalse(preview.blocking(), "整单不得被已下发线阻断");
        assertTrue(preview.unassignedRows().isEmpty());
    }

    @Test
    @DisplayName("修正：在审线 + 权重=100 → 错误组、groupError 含「正在审批中」、阻断整单")
    void shouldBlockInReviewLineEvenWhenWeightHundred() {
        stubBaseDependencies();
        Plan inReview = Plan.create(2026L, college.getId(), strategyOrg.getId(),
                PlanLevel.FUNC_TO_COLLEGE, jiaowu.getId());
        inReview.setId(903L);
        inReview.setStatus("IN_REVIEW");
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, strategyOrg.getId(), college.getId(), jiaowu.getId()))
                .thenReturn(Optional.of(inReview));

        var preview = service.previewAllDistribution(
                allImportWorkbook("in-review-weight-100.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup group = preview.groups().get(0);
        assertEquals(AllImportLineStatus.IN_REVIEW, group.lineStatus());
        assertFalse(group.willDispatch());
        assertNotNull(group.groupError());
        assertTrue(group.groupError().contains("正在审批中"), group.groupError());
        assertNull(group.skipReason(), "在审组不是跳过组");
        assertTrue(preview.blocking(), "在审组必须阻断整单提交");
    }

    @Test
    @DisplayName("修正：无线 + 权重=60 → 错误组（groupError 含 60/100）、阻断整单（既有行为不回归）")
    void shouldStillBlockWhenNoLineAndWeightNotHundred() {
        stubBaseDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("no-line-weight-60.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "60"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        AllImportGroup group = preview.groups().get(0);
        assertEquals(AllImportLineStatus.NONE, group.lineStatus());
        assertFalse(group.willDispatch());
        assertNotNull(group.groupError());
        assertTrue(group.groupError().contains("60/100"), group.groupError());
        assertNull(group.skipReason());
        assertTrue(preview.blocking(), "无线 + 权重不足必须阻断整单提交");
    }

    // ==================== 提交：组间独立事务 ====================

    @Test
    @DisplayName("提交：预览存在阻断错误（B 组权重不足）→ 整单拒绝提交")
    void shouldRejectCommitWhenPreviewHasBlockingError() {
        stubBaseDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("commit-isolation.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "60"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);
        assertTrue(preview.blocking(), "B 组权重不足应阻断");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.commitAllDistribution(
                        preview.batchId(),
                        new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                        currentUser));
        assertTrue(error.getMessage().contains("阻断错误"), error.getMessage());
    }

    @Test
    @DisplayName("提交：一组落库失败不影响他组（异常被逐组捕获）")
    void shouldContinueWithOtherGroupsWhenOneGroupThrows() {
        stubBaseDependencies();
        stubCommitDependencies();
        // 数字校园那条线的父级指标查不到 → 该组落库抛异常
        when(indicatorRepository.findById(parentShuzixiaoyuan.getId()))
                .thenThrow(new IllegalArgumentException("父级指标不存在: 2002"));

        var preview = service.previewAllDistribution(
                allImportWorkbook("commit-fail-one.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        var result = service.commitAllDistribution(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                currentUser);

        AllImportGroupResult groupA = result.results().stream()
                .filter(item -> jiaowu.getId().equals(item.functionalOrgId()))
                .findFirst().orElseThrow();
        AllImportGroupResult groupB = result.results().stream()
                .filter(item -> shuzixiaoyuan.getId().equals(item.functionalOrgId()))
                .findFirst().orElseThrow();

        assertEquals(AllImportGroupStatus.SUCCESS, groupA.status(), "教务处组应成功: " + groupA.message());
        assertEquals(AllImportGroupStatus.FAILED, groupB.status());
        assertNotNull(groupB.message());
        assertEquals(1, result.summary().success());
        assertEquals(1, result.summary().failed());
    }

    @Test
    @DisplayName("提交：已下发的组跳过（SKIPPED），不落库；其他组正常下发")
    void shouldSkipDistributedGroupOnCommit() {
        stubBaseDependencies();
        stubCommitDependencies();
        Plan distributed = Plan.create(2026L, college.getId(), strategyOrg.getId(),
                PlanLevel.FUNC_TO_COLLEGE, jiaowu.getId());
        distributed.setId(900L);
        distributed.setStatus(PlanStatus.DISTRIBUTED.value());
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, strategyOrg.getId(), college.getId(), jiaowu.getId()))
                .thenReturn(Optional.of(distributed));

        var preview = service.previewAllDistribution(
                allImportWorkbook("commit-skip.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        var result = service.commitAllDistribution(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                currentUser);

        AllImportGroupResult groupA = result.results().stream()
                .filter(item -> jiaowu.getId().equals(item.functionalOrgId()))
                .findFirst().orElseThrow();
        assertEquals(AllImportGroupStatus.SKIPPED, groupA.status());
        assertTrue(groupA.message().contains("已下发"), groupA.message());
        assertEquals(1, result.summary().skipped());
    }

    @Test
    @DisplayName("提交：confirmToken 篡改 → 阻断，不落库")
    void shouldBlockWhenConfirmTokenTampered() {
        stubBaseDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("token-tamper.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.commitAllDistribution(
                        preview.batchId(),
                        new ImportCommitRequest("sha256:tampered", ConflictMode.APPEND, false, "确认导入"),
                        currentUser));
        assertTrue(error.getMessage().contains("预览内容已变化"), error.getMessage());
    }

    @Test
    @DisplayName("幂等：同文件重复上传 → 每组命中同一条线（同线 UPDATE，不重复建线）")
    void shouldReuseSameLineOnRepeatedUpload() {
        stubBaseDependencies();

        var first = service.previewAllDistribution(
                allImportWorkbook("idempotent-1.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        var second = service.previewAllDistribution(
                allImportWorkbook("idempotent-2.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        assertEquals(first.groups().get(0).functionalOrgId(), second.groups().get(0).functionalOrgId());
        assertEquals(1, second.groups().size());
        // 两次都走同一个 5 键取线（命中同一条线），不新建无线空壳
        verify(planRepository, atLeast(2))
                .findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                        2026L, PlanLevel.FUNC_TO_COLLEGE, strategyOrg.getId(), college.getId(), jiaowu.getId());
        // 预览阶段不建线
        verify(planRepository, never()).save(any(Plan.class));
    }

    @Test
    @DisplayName("零回归：单条导入端点仍阻断跨归口文件（全部导入不影响单条路径）")
    void shouldStillBlockCrossFunctionalOrgOnSingleImport() {
        stubBaseDependencies();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.previewDistribution(
                        allImportWorkbook("single-cross-org.xlsx", new String[][]{
                                {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "50"},
                                {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "50"}
                        }),
                        2026L, college.getId(), strategyOrg.getId(), null, null, currentUser));
        assertTrue(error.getMessage().contains("多个归口部门"), error.getMessage());
        assertTrue(error.getMessage().contains("请按归口部门分别导入"), error.getMessage());
    }

    @Test
    @DisplayName("全部导入落库：两组落两条不同线（plan.functional_org_id 正确）")
    void shouldCreateTwoDistinctLinesForTwoGroups() {
        stubBaseDependencies();
        stubCommitDependencies();

        var preview = service.previewAllDistribution(
                allImportWorkbook("two-lines.xlsx", new String[][]{
                        {"1", "教务处", "父级核心指标A", "定量", "子指标A1", "定量", "100"},
                        {"2", "数字校园建设办公室", "父级核心指标B", "定量", "子指标B1", "定量", "100"}
                }),
                2026L, college.getId(), strategyOrg.getId(), null, currentUser);

        var result = service.commitAllDistribution(
                preview.batchId(),
                new ImportCommitRequest(preview.confirmToken(), ConflictMode.APPEND, false, "确认导入"),
                currentUser);

        assertEquals(2, result.summary().success(), "两组都应成功");
        assertEquals(2, result.results().size());
        for (AllImportGroupResult item : result.results()) {
            assertEquals(AllImportGroupStatus.SUCCESS, item.status(), item.message());
            assertNotNull(item.planId());
        }
        // 两条线：建线时归口分别取各自的 functional_org_id
        verify(planRepository, times(2)).save(any(Plan.class));
    }

    // ==================== 桩 ====================

    private void stubBaseDependencies() {
        lenient().when(organizationRepository.findById(strategyOrg.getId()))
                .thenReturn(Optional.of(strategyOrg));
        lenient().when(organizationRepository.findById(college.getId()))
                .thenReturn(Optional.of(college));
        lenient().when(organizationRepository.findById(jiaowu.getId()))
                .thenReturn(Optional.of(jiaowu));
        lenient().when(organizationRepository.findById(shuzixiaoyuan.getId()))
                .thenReturn(Optional.of(shuzixiaoyuan));
        lenient().when(organizationRepository.findByNameContaining("教务处"))
                .thenReturn(List.of(jiaowu));
        lenient().when(organizationRepository.findByNameContaining("数字校园建设办公室"))
                .thenReturn(List.of(shuzixiaoyuan));
        lenient().when(indicatorRepository.findCoreDispatchAnchors(strategyOrg.getId(), 2026L))
                .thenReturn(List.of(parentJiaowu, parentShuzixiaoyuan));
        lenient().when(indicatorRepository.findCoreIndicatorsInCycle(strategyOrg.getId(), 2026L))
                .thenReturn(List.of());
        lenient().when(planRepository.findByCycleId(2026L)).thenReturn(List.of());
        // 取线（5 键）：默认无线
        lenient().when(planRepository
                .findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                        anyLong(), any(PlanLevel.class), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(taskRepository.findById(parentTask.getId())).thenReturn(Optional.of(parentTask));
        lenient().when(cycleRepository.findById(2026L)).thenReturn(Optional.of(cycle2026()));
    }

    private com.sism.strategy.domain.cycle.Cycle cycle2026() {
        com.sism.strategy.domain.cycle.Cycle cycle =
                com.sism.strategy.domain.cycle.Cycle.create(
                        "2026年度战略目标考核", 2026, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        cycle.setId(2026L);
        return cycle;
    }

    /** 2026-11-19：把某归口部门的「线」桩成已下发（DISTRIBUTED）。 */
    private void stubDistributedLine(Long functionalOrgId) {
        Plan distributed = Plan.create(2026L, college.getId(), strategyOrg.getId(),
                PlanLevel.FUNC_TO_COLLEGE, functionalOrgId);
        distributed.setId(900L + functionalOrgId);
        distributed.setStatus(PlanStatus.DISTRIBUTED.value());
        when(planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                2026L, PlanLevel.FUNC_TO_COLLEGE, strategyOrg.getId(), college.getId(), functionalOrgId))
                .thenReturn(Optional.of(distributed));
    }

    private void stubCommitDependencies() {
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
        lenient().when(indicatorRepository.findById(parentJiaowu.getId())).thenReturn(Optional.of(parentJiaowu));
        lenient().when(indicatorRepository.findById(parentShuzixiaoyuan.getId())).thenReturn(Optional.of(parentShuzixiaoyuan));
        lenient().when(taskRepository.findByPlanIdAndCycleId(anyLong(), anyLong())).thenReturn(List.of());
        // 建任务：findOrCreateTask 找不到同名任务时会 save 一条新任务
        AtomicLong taskIds = new AtomicLong(700L);
        lenient().when(taskRepository.save(any(StrategicTask.class))).thenAnswer(invocation -> {
            StrategicTask task = invocation.getArgument(0);
            task.setId(taskIds.incrementAndGet());
            return task;
        });

        AtomicLong indicatorIds = new AtomicLong(3000L);
        when(strategyApplicationService.createIndicator(
                anyString(), any(SysOrg.class), any(SysOrg.class), anyLong(), anyLong(),
                anyString(), any(BigDecimal.class), any(), any(), any()))
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

    /**
     * 构建「全部导入」工作簿（9 列：序号 / 学院 / 职能部门 / 任务类型 / 核心指标 /
     * 核心指标类型 / 子指标名称 / 指标类型 / 权重）。
     *
     * @param rows 每行 = {序号, 职能部门, 核心指标, 核心指标类型, 子指标名称, 指标类型, 权重}
     */
    private MockMultipartFile allImportWorkbook(String fileName, String[][] rows) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学院子指标模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("学院");
            header.createCell(2).setCellValue("职能部门");
            header.createCell(3).setCellValue("任务类型");
            header.createCell(4).setCellValue("核心指标");
            header.createCell(5).setCellValue("核心指标类型");
            header.createCell(6).setCellValue("子指标名称");
            header.createCell(7).setCellValue("指标类型");
            header.createCell(8).setCellValue("权重（纯数字）");

            for (int index = 0; index < rows.length; index++) {
                String[] data = rows[index];
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(Integer.parseInt(data[0]));
                row.createCell(1).setCellValue(college.getName());
                if (data[1] != null && !data[1].isBlank()) {
                    row.createCell(2).setCellValue(data[1]);
                }
                row.createCell(3).setCellValue("基础性");
                row.createCell(4).setCellValue(data[2]);
                row.createCell(5).setCellValue(data[3]);
                row.createCell(6).setCellValue(data[4]);
                row.createCell(7).setCellValue(data[5]);
                row.createCell(8).setCellValue(Double.parseDouble(data[6]));
            }

            workbook.write(outputStream);
            return new MockMultipartFile(
                    "file", fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    outputStream.toByteArray());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
