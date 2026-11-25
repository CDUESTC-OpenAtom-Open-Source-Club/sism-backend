package com.sism.main.application;

import com.sism.execution.application.ReportApplicationService;
import com.sism.execution.domain.report.PlanReport;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitRequest;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitResponse;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillGroup;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillGroupStatus;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillPreviewResponse;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import com.sism.task.domain.repository.TaskRepository;
import com.sism.task.domain.task.StrategicTask;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-25（指标填报导入式上报）：编排服务单测。
 *
 * <p>口径来源 = 标准模板 04/05/06 的「填写说明」：
 * <ul>
 *   <li>责任部门必须 = 登录人所在部门/学院，不一致<b>整单拒绝</b>；</li>
 *   <li>06 按「目标部门」拆线，各线独立（一条线失败不影响其他线）；</li>
 *   <li>05 定位键 = 责任部门 + 目标部门 + 子指标名称；04 定位键 = 责任部门 + 子指标名称；</li>
 *   <li>自评等级严格三选一「超前完成 / 正常 / 滞后」；</li>
 *   <li>04 的上报目标必须是战略发展部。</li>
 * </ul>
 */
class ReportFillImportApplicationServiceTest {

    private static final Long COLLEGE_ID = 60L;
    private static final Long DEPT_ID = 44L;
    private static final Long STRATEGY_ORG_ID = 35L;
    private static final Long CYCLE_ID = 4L;

    /** 05/06 学院上报表头（8 列，新模板） */
    private static final String[] COLLEGE_HEADERS = {
            "序号",
            "责任部门\n（来源·须与登录学院一致）",
            "目标部门\n（归口职能部门）",
            "子指标名称",
            "权重\n（系统只读）",
            "自评等级\n（点选填写）",
            "进度说明\n（请填写）",
            "（枚举来源，勿动）"
    };

    /** 04 职能部门上报表头（8 列，第 3 列是「上报目标」） */
    private static final String[] DEPT_HEADERS = {
            "序号",
            "责任部门\n（来源·须与登录部门一致）",
            "上报目标\n（战略发展部）",
            "子指标名称",
            "权重\n（系统只读）",
            "自评等级\n（点选填写）",
            "进度说明\n（请填写）",
            "（枚举来源，勿动）"
    };

    private OrganizationRepository organizationRepository;
    private PlanRepository planRepository;
    private TaskRepository taskRepository;
    private IndicatorRepository indicatorRepository;
    private ReportApplicationService reportApplicationService;
    private com.sism.execution.domain.report.PlanReportRepository planReportRepository;
    private TransactionTemplate transactionTemplate;
    private ReportFillImportApplicationService service;

    private SysOrg college;
    private SysOrg dept;
    private SysOrg strategyOrg;

    /** 归口部门 id → 该线已下发的子指标名（学院 05/06） */
    private final Map<Long, List<String>> lineIndicators = new LinkedHashMap<>();
    /** 该计划下已下发的核心指标名（职能部门 04） */
    private List<String> deptCoreIndicators = List.of();

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        planRepository = mock(PlanRepository.class);
        taskRepository = mock(TaskRepository.class);
        indicatorRepository = mock(IndicatorRepository.class);
        reportApplicationService = mock(ReportApplicationService.class);
        planReportRepository = mock(com.sism.execution.domain.report.PlanReportRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        StrategyOrgProperties properties = mock(StrategyOrgProperties.class);
        when(properties.getStrategyOrgId()).thenReturn(STRATEGY_ORG_ID);

        service = new ReportFillImportApplicationService(
                new ReportFillImportParser(),
                organizationRepository,
                planRepository,
                taskRepository,
                indicatorRepository,
                properties,
                reportApplicationService,
                planReportRepository,
                transactionTemplate);

        college = org("计算机学院", OrgType.academic, COLLEGE_ID);
        dept = org("教务处", OrgType.functional, DEPT_ID);
        strategyOrg = org("战略发展部", OrgType.functional, STRATEGY_ORG_ID);

        when(organizationRepository.findById(COLLEGE_ID)).thenReturn(Optional.of(college));
        when(organizationRepository.findById(DEPT_ID)).thenReturn(Optional.of(dept));
        when(organizationRepository.findById(STRATEGY_ORG_ID)).thenReturn(Optional.of(strategyOrg));
        when(organizationRepository.findByNameContaining("什邡管委会"))
                .thenReturn(List.of(org("什邡管委会", OrgType.functional, 45L)));
        when(organizationRepository.findByNameContaining("预算部"))
                .thenReturn(List.of(org("预算部", OrgType.functional, 46L)));
    }

    private static SysOrg org(String name, OrgType type, Long id) {
        SysOrg org = SysOrg.create(name, type);
        org.setId(id);
        return org;
    }

    private CurrentUser collegeReporter() {
        return new CurrentUser(9001L, "jisuanji_report", "计算机学院填报人", "t@example.com",
                COLLEGE_ID, List.of());
    }

    private CurrentUser deptReporter() {
        return new CurrentUser(9002L, "jiaowu_report", "教务处填报人", "f@example.com",
                DEPT_ID, List.of());
    }

    // ==================== Excel 构造 ====================

    private MockMultipartFile file(String[] headers, String... dataRows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("月报填报导入模板");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < dataRows.length; r++) {
                String[] cells = dataRows[r].split("\\|", -1);
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < cells.length; c++) {
                    row.createCell(c).setCellValue(cells[c]);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile("file", "月报填报导入模板.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MockMultipartFile collegeFile(String... rows) {
        return file(COLLEGE_HEADERS, rows);
    }

    private MockMultipartFile deptFile(String... rows) {
        return file(DEPT_HEADERS, rows);
    }

    // ==================== 已下发线 ====================

    private void givenCollegeLine(Long functionalOrgId, String... indicatorNames) {
        lineIndicators.put(functionalOrgId, List.of(indicatorNames));
        applyStubs();
    }

    private void givenDeptPlan(String... coreIndicatorNames) {
        deptCoreIndicators = List.of(coreIndicatorNames);
        applyStubs();
    }

    private void applyStubs() {
        // 学院线：5 键 → 归口线计划（planId = 2000 + 归口id）
        Mockito.doAnswer(invocation -> {
            Long functionalOrgId = invocation.getArgument(4);
            if (!lineIndicators.containsKey(functionalOrgId)) {
                return Optional.empty();
            }
            Plan plan = new Plan();
            plan.setId(2000L + functionalOrgId);
            plan.setCycleId(CYCLE_ID);
            plan.setFunctionalOrgId(functionalOrgId);
            plan.setTargetOrgId(COLLEGE_ID);
            plan.setCreatedByOrgId(STRATEGY_ORG_ID);
            return Optional.of(plan);
        }).when(planRepository)
                .findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                        anyLong(), any(), anyLong(), anyLong(), anyLong());

        // 职能部门：STRAT_TO_FUNC
        Mockito.doAnswer(invocation -> {
            Long targetOrgId = invocation.getArgument(3);
            if (deptCoreIndicators.isEmpty()) {
                return Optional.empty();
            }
            Plan plan = new Plan();
            plan.setId(3000L + targetOrgId);
            plan.setCycleId(CYCLE_ID);
            plan.setTargetOrgId(targetOrgId);
            plan.setCreatedByOrgId(STRATEGY_ORG_ID);
            return Optional.of(plan);
        }).when(planRepository)
                .findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                        anyLong(), any(), anyLong(), anyLong());

        // 任务：按 planId 分派（2000+归口 / 3000+组织）
        Mockito.doAnswer(invocation -> {
            Long planId = invocation.getArgument(0);
            StrategicTask task = mock(StrategicTask.class);
            when(task.getId()).thenReturn(planId + 100000L);
            return List.of(task);
        }).when(taskRepository).findByPlanId(anyLong());

        // 指标：按 taskId 反推是学院线还是职能部门
        Mockito.doAnswer(invocation -> {
            List<Long> taskIds = invocation.getArgument(0);
            if (taskIds == null || taskIds.isEmpty()) {
                return List.of();
            }
            long syntheticPlanId = taskIds.get(0) - 100000L;
            List<String> names;
            boolean child;
            if (syntheticPlanId >= 3000L) {
                names = deptCoreIndicators;
                child = false;
            } else {
                names = lineIndicators.get(syntheticPlanId - 2000L);
                child = true;
            }
            if (names == null || names.isEmpty()) {
                return List.of();
            }
            java.util.List<Indicator> list = new java.util.ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                Indicator indicator = mock(Indicator.class);
                when(indicator.getId()).thenReturn(9000L + i);
                when(indicator.getIndicatorDesc()).thenReturn(names.get(i));
                when(indicator.getParentIndicatorId()).thenReturn(child ? 1L : null);
                when(indicator.getTargetOrg()).thenReturn(
                        syntheticPlanId >= 3000L ? dept : college);
                when(indicator.getIsDeleted()).thenReturn(false);
                list.add(indicator);
            }
            return list;
        }).when(indicatorRepository).findByTaskIds(any());
    }

    // ==================== 责任部门校验 ====================

    @Test
    @DisplayName("责任部门与登录学院不一致 → 整单拒绝")
    void responsibleDeptMismatchRejectsWholeFile() {
        MockMultipartFile wrong = collegeFile("1|其他学院|什邡管委会|指标A|40|正常|说明|超前完成");

        assertThatThrownBy(() -> service.preview(wrong, CYCLE_ID, "202611", "all", null, null, collegeReporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("整单拒绝导入")
                .hasMessageContaining("其他学院");
    }

    @Test
    @DisplayName("责任部门留空 → 行级错误（不整单拒绝）")
    void blankResponsibleDeptIsRowError() {
        MockMultipartFile blank = collegeFile("1||什邡管委会|指标A|40|正常|说明|超前完成");
        givenCollegeLine(45L, "指标A");

        ReportFillPreviewResponse preview = service.preview(
                blank, CYCLE_ID, "202611", "all", null, null, collegeReporter());

        assertThat(preview.groups().get(0).rows().get(0).errors())
                .anyMatch(message -> message.contains("责任部门不能为空"));
    }

    // ==================== 学院 05/06 ====================

    @Test
    @DisplayName("06 批量：按「目标部门」列拆线，两条线各自独立")
    void collegeSplitsByTargetDept() {
        MockMultipartFile multi = collegeFile(
                "1|计算机学院|什邡管委会|指标A|40|正常|说明A|超前完成",
                "2|计算机学院|预算部|指标B|30|超前完成|说明B|正常");
        givenCollegeLine(45L, "指标A");
        givenCollegeLine(46L, "指标B");

        ReportFillPreviewResponse preview = service.preview(
                multi, CYCLE_ID, "202611", "all", null, null, collegeReporter());

        assertThat(preview.reportType()).isEqualTo("COLLEGE");
        assertThat(preview.groups()).hasSize(2);
        assertThat(preview.groups().get(0).targetOrgName()).isEqualTo("什邡管委会");
        assertThat(preview.groups().get(1).targetOrgName()).isEqualTo("预算部");
        assertThat(preview.groups()).allMatch(ReportFillGroup::willSubmit);
        assertThat(preview.blocking()).isFalse();
    }

    @Test
    @DisplayName("05 单归口：只提交所选目标部门，其它线标记为将跳过且不报「找不到子指标」")
    void collegeSingleModeSkipsOtherLines() {
        MockMultipartFile multi = collegeFile(
                "1|计算机学院|什邡管委会|指标A|40|正常|说明A|超前完成",
                "2|计算机学院|预算部|指标B|30|正常|说明B|正常");
        givenCollegeLine(45L, "指标A");
        givenCollegeLine(46L, "指标B");

        ReportFillPreviewResponse preview = service.preview(
                multi, CYCLE_ID, "202611", "single", 45L, null, collegeReporter());

        assertThat(preview.groups().get(0).willSubmit()).isTrue();
        ReportFillGroup other = preview.groups().get(1);
        assertThat(other.willSubmit()).isFalse();
        assertThat(other.skipReason()).contains("本次不提交");
        assertThat(other.rows()).noneMatch(row ->
                row.errors() != null && row.errors().stream().anyMatch(m -> m.contains("没有找到子指标")));
    }

    @Test
    @DisplayName("单归口模式未给 targetOrgId → 服务端直接拒绝")
    void singleModeWithoutTargetOrgIsRejected() {
        MockMultipartFile one = collegeFile("1|计算机学院|什邡管委会|指标A|40|正常|说明|超前完成");
        givenCollegeLine(45L, "指标A");

        assertThatThrownBy(() -> service.preview(
                one, CYCLE_ID, "202611", "single", null, null, collegeReporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单归口导入必须指定目标部门");
    }

    @Test
    @DisplayName("等级非法 → 行级点名，该线不可提交")
    void illegalLevelIsRowError() {
        MockMultipartFile bad = collegeFile("1|计算机学院|什邡管委会|指标A|40|超前|说明|超前完成");
        givenCollegeLine(45L, "指标A");

        ReportFillPreviewResponse preview = service.preview(
                bad, CYCLE_ID, "202611", "all", null, null, collegeReporter());

        assertThat(preview.groups().get(0).rows().get(0).errors())
                .anyMatch(message -> message.contains("超前完成 / 正常 / 滞后"));
        assertThat(preview.groups().get(0).willSubmit()).isFalse();
        assertThat(preview.blocking()).isTrue();
    }

    @Test
    @DisplayName("子指标名找不到 → 该行失败点名")
    void unknownIndicatorIsRowError() {
        MockMultipartFile mixed = collegeFile(
                "1|计算机学院|什邡管委会|指标A|40|正常|说明A|超前完成",
                "2|计算机学院|什邡管委会|不存在的指标|40|正常|说明B|正常");
        givenCollegeLine(45L, "指标A");

        ReportFillPreviewResponse preview = service.preview(
                mixed, CYCLE_ID, "202611", "all", null, null, collegeReporter());

        assertThat(preview.groups().get(0).rows().get(0).indicatorId()).isNotNull();
        assertThat(preview.groups().get(0).rows().get(0).level()).isEqualTo("NORMAL");
        assertThat(preview.groups().get(0).rows().get(1).errors())
                .anyMatch(message -> message.contains("没有找到子指标"));
    }

    @Test
    @DisplayName("本周期找不到（学院 × 目标部门）的下发计划 → 组级错误")
    void missingCollegeLineIsGroupError() {
        MockMultipartFile orphan = collegeFile("1|计算机学院|什邡管委会|指标A|40|正常|说明|超前完成");

        ReportFillPreviewResponse preview = service.preview(
                orphan, CYCLE_ID, "202611", "all", null, null, collegeReporter());

        assertThat(preview.groups().get(0).groupError()).contains("未找到");
        assertThat(preview.blocking()).isTrue();
    }

    @Test
    @DisplayName("同一线内同一子指标重复两行 → 第二行点名")
    void duplicateIndicatorRowsAreNamed() {
        MockMultipartFile dup = collegeFile(
                "1|计算机学院|什邡管委会|指标A|40|正常|说明A|超前完成",
                "2|计算机学院|什邡管委会|指标A|40|正常|说明A2|正常");
        givenCollegeLine(45L, "指标A");

        ReportFillPreviewResponse preview = service.preview(
                dup, CYCLE_ID, "202611", "all", null, null, collegeReporter());

        assertThat(preview.groups().get(0).rows().get(1).errors())
                .anyMatch(message -> message.contains("重复填报同一子指标"));
    }

    // ==================== 职能部门 04 ====================

    @Test
    @DisplayName("04 职能部门上报：单线、目标恒为战略发展部、定位到核心指标")
    void functionalReportIsSingleLineToStrategy() {
        MockMultipartFile file = deptFile(
                "1|教务处|战略发展部|推进教务处教学质量提升专项工作落地见效|40|正常|说明A|超前完成",
                "2|教务处|战略发展部|完善课程建设与专业评估机制|60|正常|说明B|正常");
        givenDeptPlan("推进教务处教学质量提升专项工作落地见效", "完善课程建设与专业评估机制");

        ReportFillPreviewResponse preview = service.preview(
                file, CYCLE_ID, "202611", "all", null, null, deptReporter());

        assertThat(preview.reportType()).isEqualTo("FUNC_DEPT");
        assertThat(preview.groups()).hasSize(1);
        assertThat(preview.groups().get(0).targetOrgName()).isEqualTo("战略发展部");
        assertThat(preview.groups().get(0).willSubmit()).isTrue();
        assertThat(preview.groups().get(0).rows()).allMatch(row -> row.indicatorId() != null);
    }

    @Test
    @DisplayName("04 上报目标不是战略发展部 → 行级点名，该线不可提交")
    void functionalReportTargetMustBeStrategy() {
        MockMultipartFile file = deptFile("1|教务处|教务处|推进教务处教学质量提升专项工作落地见效|40|正常|说明|超前完成");
        givenDeptPlan("推进教务处教学质量提升专项工作落地见效");

        ReportFillPreviewResponse preview = service.preview(
                file, CYCLE_ID, "202611", "all", null, null, deptReporter());

        assertThat(preview.groups().get(0).rows().get(0).errors())
                .anyMatch(message -> message.contains("上报目标应为「战略发展部」"));
        assertThat(preview.groups().get(0).willSubmit()).isFalse();
    }

    @Test
    @DisplayName("04 本周期找不到（战略部 → 部门）的下发计划 → 组级错误")
    void functionalReportWithoutPlanIsGroupError() {
        MockMultipartFile file = deptFile("1|教务处|战略发展部|指标A|40|正常|说明|超前完成");

        ReportFillPreviewResponse preview = service.preview(
                file, CYCLE_ID, "202611", "all", null, null, deptReporter());

        assertThat(preview.groups().get(0).groupError()).contains("未找到");
        assertThat(preview.blocking()).isTrue();
    }

    // ==================== 通用守卫 ====================

    @Test
    @DisplayName("只有表头没有数据行 → 直接拒绝")
    void headerOnlyFileIsRejected() {
        MockMultipartFile empty = collegeFile();

        assertThatThrownBy(() -> service.preview(empty, CYCLE_ID, "202611", "all", null, null, collegeReporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可导入的数据行");
    }

    @Test
    @DisplayName("管理员组织（admin）→ 拒绝使用导入式上报")
    void adminOrgIsRejected() {
        when(organizationRepository.findById(1L))
                .thenReturn(Optional.of(org("系统管理", OrgType.admin, 1L)));
        CurrentUser admin = new CurrentUser(9003L, "admin", "管理员", "a@example.com", 1L, List.of());
        MockMultipartFile one = collegeFile("1|计算机学院|什邡管委会|指标A|40|正常|说明|超前完成");

        assertThatThrownBy(() -> service.preview(one, CYCLE_ID, "202611", "all", null, null, admin))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("二级学院或职能部门");
    }

    @Test
    @DisplayName("目标部门同名多条 → 组级错误点名")
    void ambiguousTargetDeptIsGroupError() {
        when(organizationRepository.findByNameContaining("教务处"))
                .thenReturn(List.of(org("教务处", OrgType.functional, 46L), org("教务处", OrgType.functional, 47L)));
        MockMultipartFile dup = collegeFile("1|计算机学院|教务处|指标A|40|正常|说明|超前完成");

        ReportFillPreviewResponse preview = service.preview(
                dup, CYCLE_ID, "202611", "all", null, null, collegeReporter());

        assertThat(preview.groups().get(0).groupError()).contains("多个同名组织");
        assertThat(preview.blocking()).isTrue();
    }

    @Test
    @DisplayName("commit：逐线独立事务 —— 一条线失败不影响他线")
    void commitIsolatesFailuresPerLine() {
        MockMultipartFile multi = collegeFile(
                "1|计算机学院|什邡管委会|指标A|40|正常|说明A|超前完成",
                "2|计算机学院|预算部|指标B|30|正常|说明B|正常");
        givenCollegeLine(45L, "指标A");
        givenCollegeLine(46L, "指标B");

        // 每条线各自的月报：45 → 1045，46 → 1046
        when(reportApplicationService.createReport(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Long functionalOrgId = invocation.getArgument(5);
                    PlanReport lineReport = mock(PlanReport.class);
                    when(lineReport.getId()).thenReturn(1000L + (functionalOrgId == null ? 0L : functionalOrgId));
                    return lineReport;
                });
        when(reportApplicationService.upsertFillDetails(any(), any(), any())).thenAnswer(invocation -> {
            Long reportId = invocation.getArgument(0);
            if (Long.valueOf(1045L).equals(reportId)) {
                throw new IllegalStateException("boom");
            }
            PlanReport lineReport = mock(PlanReport.class);
            when(lineReport.getId()).thenReturn(reportId);
            return lineReport;
        });

        ReportFillPreviewResponse preview = service.preview(
                multi, CYCLE_ID, "202611", "all", null, null, collegeReporter());
        ReportFillCommitResponse response = service.commit(preview.batchId(),
                new ReportFillCommitRequest(preview.confirmToken(), "", null), collegeReporter());

        assertThat(response.results()).hasSize(2);
        assertThat(response.results()).anyMatch(r -> r.status() == ReportFillGroupStatus.FAILED);
        assertThat(response.results()).anyMatch(r -> r.status() == ReportFillGroupStatus.SUCCESS);
        assertThat(response.summary().failed()).isEqualTo(1);
        assertThat(response.summary().success()).isEqualTo(1);
    }

    @Test
    @DisplayName("commit：确认令牌不匹配 → 拒绝")
    void commitRejectsWrongConfirmToken() {
        MockMultipartFile one = collegeFile("1|计算机学院|什邡管委会|指标A|40|正常|说明|超前完成");
        givenCollegeLine(45L, "指标A");

        ReportFillPreviewResponse preview = service.preview(
                one, CYCLE_ID, "202611", "all", null, null, collegeReporter());

        assertThatThrownBy(() -> service.commit(preview.batchId(),
                new ReportFillCommitRequest("wrong-token", "", null), collegeReporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确认令牌");
    }

    @Test
    @DisplayName("commit：别人上传的批次 → 拒绝")
    void commitRejectsOtherUsersBatch() {
        MockMultipartFile one = collegeFile("1|计算机学院|什邡管委会|指标A|40|正常|说明|超前完成");
        givenCollegeLine(45L, "指标A");

        ReportFillPreviewResponse preview = service.preview(
                one, CYCLE_ID, "202611", "all", null, null, collegeReporter());
        CurrentUser other = new CurrentUser(9004L, "other", "别人", "o@example.com", COLLEGE_ID, List.of());

        assertThatThrownBy(() -> service.commit(preview.batchId(),
                new ReportFillCommitRequest(preview.confirmToken(), "", null), other))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("只能确认自己上传的导入批次");
    }

    @Test
    @DisplayName("commit：整单没有任何可提交线 → 服务端拒绝")
    void commitRejectsWhenNoSubmittableGroup() {
        MockMultipartFile bad = collegeFile("1|计算机学院|什邡管委会|指标A|40|超前|说明|超前完成");
        givenCollegeLine(45L, "指标A");

        ReportFillPreviewResponse preview = service.preview(
                bad, CYCLE_ID, "202611", "all", null, null, collegeReporter());
        assertThat(preview.hasSubmittableGroup()).isFalse();

        assertThatThrownBy(() -> service.commit(preview.batchId(),
                new ReportFillCommitRequest(preview.confirmToken(), "", null), collegeReporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全部上报线均不可提交");
    }
}
