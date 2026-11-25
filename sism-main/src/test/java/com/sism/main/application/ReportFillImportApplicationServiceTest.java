package com.sism.main.application;

import com.sism.execution.application.ReportApplicationService;
import com.sism.execution.domain.report.PlanReport;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitRequest;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitResponse;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillGroupStatus;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillPreviewResponse;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-25（指标填报导入式上报 · 学院侧）：编排服务单测。
 *
 * <p>锁住模板「填写说明」里的四条核心业务规则：
 * <ol>
 *   <li>第 3 条：学院列必须 = 登录人所在学院，不一致<b>整单拒绝</b>；</li>
 *   <li>第 4 条（B 版）：按「归口职能部门」列自动拆线，各线独立；</li>
 *   <li>第 5 条：定位键 = 学院 + 归口职能部门 + 子指标名称，无匹配 = 该行失败点名；</li>
 *   <li>第 6 条：自评等级严格白名单，非法值行级点名。</li>
 * </ol>
 * 另加：commit 逐线独立事务（一条线失败不影响他线）。
 */
class ReportFillImportApplicationServiceTest {

    private static final Long COLLEGE_ID = 60L;
    private static final Long STRATEGY_ORG_ID = 35L;
    private static final Long CYCLE_ID = 4L;
    private static final String[] HEADERS = {
            "序号",
            "学院\n（须与登录学院一致）",
            "归口职能部门",
            "子指标名称",
            "权重\n（系统只读）",
            "自评等级\n（点选填写）",
            "进度说明\n（请填写）"
    };

    private OrganizationRepository organizationRepository;
    private PlanRepository planRepository;
    private TaskRepository taskRepository;
    private IndicatorRepository indicatorRepository;
    private ReportApplicationService reportApplicationService;
    private TransactionTemplate transactionTemplate;
    private ReportFillImportApplicationService service;

    private SysOrg college;
    private SysOrg functionalDept;
    private SysOrg otherFunctionalDept;
    /** 归口部门 id → 该线下已下发的子指标名（多条线累积） */
    private final Map<Long, List<String>> lineIndicators = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        planRepository = mock(PlanRepository.class);
        taskRepository = mock(TaskRepository.class);
        indicatorRepository = mock(IndicatorRepository.class);
        reportApplicationService = mock(ReportApplicationService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        service = new ReportFillImportApplicationService(
                new ReportFillImportParser(),
                organizationRepository,
                planRepository,
                taskRepository,
                indicatorRepository,
                mock(StrategyOrgProperties.class),
                reportApplicationService,
                transactionTemplate);

        college = org("计算机学院", OrgType.academic, COLLEGE_ID);
        functionalDept = org("什邡管委会", OrgType.functional, 44L);
        otherFunctionalDept = org("预算部", OrgType.functional, 45L);

        when(organizationRepository.findById(COLLEGE_ID)).thenReturn(Optional.of(college));
        when(organizationRepository.findByNameContaining("什邡管委会")).thenReturn(List.of(functionalDept));
        when(organizationRepository.findByNameContaining("预算部")).thenReturn(List.of(otherFunctionalDept));
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

    // ==================== Excel 构造 ====================

    private MockMultipartFile file(String... dataRows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("月报填报导入模板");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
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

    // ==================== 已下发线与指标 ====================

    private void givenLine(Long functionalOrgId, String... indicatorNames) {
        // 注册式：多条线累积到同一张表，最后统一 stub——
        // 若分多次 stub 同一个方法，后一次会覆盖前一次，先注册的那条线就查不到指标。
        lineIndicators.put(functionalOrgId, List.of(indicatorNames));
        applyLineStubs();
    }

    private void applyLineStubs() {
        Mockito.doAnswer(invocation -> {
            Long funcOrg = invocation.getArgument(4);
            Plan plan = new Plan();
            plan.setId(1000L + funcOrg);
            plan.setCycleId(CYCLE_ID);
            plan.setFunctionalOrgId(funcOrg);
            plan.setTargetOrgId(COLLEGE_ID);
            plan.setCreatedByOrgId(STRATEGY_ORG_ID);
            return Optional.of(plan);
        }).when(planRepository)
                .findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                        anyLong(), any(), anyLong(), anyLong(), anyLong());

        Mockito.doAnswer(invocation -> {
            Long planId = invocation.getArgument(0);
            StrategicTask lineTask = mock(StrategicTask.class);
            when(lineTask.getId()).thenReturn(planId - 1000L + 500L);
            return List.of(lineTask);
        }).when(taskRepository).findByPlanId(anyLong());

        Mockito.doAnswer(invocation -> {
            java.util.List<Long> taskIds = invocation.getArgument(0);
            if (taskIds == null || taskIds.isEmpty()) {
                return List.of();
            }
            Long ownerFuncOrg = taskIds.get(0) - 500L;
            java.util.List<String> names = lineIndicators.get(ownerFuncOrg);
            if (names == null) {
                return List.of();
            }
            java.util.List<Indicator> indicators = new java.util.ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                Indicator indicator = mock(Indicator.class);
                when(indicator.getId()).thenReturn(9000L + i);
                when(indicator.getIndicatorDesc()).thenReturn(names.get(i));
                when(indicator.getParentIndicatorId()).thenReturn(1L);
                when(indicator.getTargetOrg()).thenReturn(college);
                when(indicator.getIsDeleted()).thenReturn(false);
                indicators.add(indicator);
            }
            return indicators;
        }).when(indicatorRepository).findByTaskIds(any());
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("学院列与登录学院不一致 → 整单拒绝（抛异常，非行级错误）")
    void collegeColumnMismatchRejectsWholeFile() {
        MockMultipartFile wrong = file("1|其他学院|什邡管委会|指标A|40|正常|说明");

        assertThatThrownBy(() -> service.preview(wrong, CYCLE_ID, "202611", null, null, collegeReporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("整单拒绝导入")
                .hasMessageContaining("其他学院");
    }

    @Test
    @DisplayName("学院列留空 → 不整单拒绝（列级错误在行内点名）")
    void blankCollegeColumnIsRowErrorNotWholeFileRejection() {
        MockMultipartFile blank = file("1||什邡管委会|指标A|40|正常|说明");
        givenLine(44L, "指标A");

        ReportFillPreviewResponse preview = service.preview(blank, CYCLE_ID, "202611", null, null, collegeReporter());

        assertThat(preview.groups()).hasSize(1);
        assertThat(preview.groups().get(0).rows().get(0).errors())
                .anyMatch(message -> message.contains("学院不能为空"));
    }

    @Test
    @DisplayName("按「归口职能部门」列自动拆线：两个部门 = 两组，各自独立")
    void splitsByFunctionalDeptColumn() {
        MockMultipartFile multi = file(
                "1|计算机学院|什邡管委会|指标A|40|正常|说明A",
                "2|计算机学院|预算部|指标B|30|超前完成|说明B");
        givenLine(44L, "指标A");
        givenLine(45L, "指标B");

        ReportFillPreviewResponse preview = service.preview(multi, CYCLE_ID, "202611", null, null, collegeReporter());

        assertThat(preview.groups()).hasSize(2);
        assertThat(preview.groups().get(0).functionalOrgName()).isEqualTo("什邡管委会");
        assertThat(preview.groups().get(1).functionalOrgName()).isEqualTo("预算部");
        assertThat(preview.groups()).allMatch(group -> group.willSubmit());
        assertThat(preview.blocking()).isFalse();
    }

    @Test
    @DisplayName("等级非法 → 行级点名（不静默放行），该组不可提交")
    void illegalLevelIsNamedRowError() {
        MockMultipartFile bad = file("1|计算机学院|什邡管委会|指标A|40|超前|说明");
        givenLine(44L, "指标A");

        ReportFillPreviewResponse preview = service.preview(bad, CYCLE_ID, "202611", null, null, collegeReporter());

        assertThat(preview.groups()).hasSize(1);
        assertThat(preview.groups().get(0).rows().get(0).errors())
                .anyMatch(message -> message.contains("超前完成 / 正常 / 滞后"));
        assertThat(preview.groups().get(0).willSubmit()).isFalse();
        assertThat(preview.blocking()).isTrue();
    }

    @Test
    @DisplayName("子指标名找不到 → 该行失败点名（不阻断其它行）")
    void unknownIndicatorIsNamedRowError() {
        MockMultipartFile mixed = file(
                "1|计算机学院|什邡管委会|指标A|40|正常|说明A",
                "2|计算机学院|什邡管委会|不存在的指标|40|正常|说明B");
        givenLine(44L, "指标A");

        ReportFillPreviewResponse preview = service.preview(mixed, CYCLE_ID, "202611", null, null, collegeReporter());

        assertThat(preview.groups().get(0).rows()).hasSize(2);
        assertThat(preview.groups().get(0).rows().get(0).errors()).isEmpty();
        assertThat(preview.groups().get(0).rows().get(0).indicatorId()).isNotNull();
        assertThat(preview.groups().get(0).rows().get(0).level()).isEqualTo("NORMAL");
        assertThat(preview.groups().get(0).rows().get(1).errors())
                .anyMatch(message -> message.contains("没有找到子指标"));
    }

    @Test
    @DisplayName("本周期找不到（学院 × 归口）的下发计划 → 组级错误，阻断整单")
    void missingLinePlanIsGroupError() {
        MockMultipartFile orphan = file("1|计算机学院|什邡管委会|指标A|40|正常|说明");
        // 未 givenLine → 计划线查不到

        ReportFillPreviewResponse preview = service.preview(orphan, CYCLE_ID, "202611", null, null, collegeReporter());

        assertThat(preview.groups().get(0).groupError()).contains("未找到");
        assertThat(preview.groups().get(0).willSubmit()).isFalse();
        assertThat(preview.blocking()).isTrue();
    }

    @Test
    @DisplayName("commit：逐线独立事务 —— 一条线失败不影响他线")
    void commitIsolatesFailuresPerLine() {
        MockMultipartFile multi = file(
                "1|计算机学院|什邡管委会|指标A|40|正常|说明A",
                "2|计算机学院|预算部|指标B|30|超前完成|说明B");
        givenLine(44L, "指标A");
        givenLine(45L, "指标B");

        PlanReport report = mock(PlanReport.class);
        when(report.getId()).thenReturn(777L);
        // 每条线各自的月报：什邡管委会线 = 777，预算部线 = 778
        when(reportApplicationService.createReport(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Long functionalOrgId = invocation.getArgument(5);
                    PlanReport lineReport = mock(PlanReport.class);
                    when(lineReport.getId()).thenReturn(functionalOrgId == null ? 777L : 777L + functionalOrgId);
                    return lineReport;
                });
        // 什邡管委会线（reportId 821）落库失败；预算部线（831）成功
        when(reportApplicationService.upsertFillDetails(any(), any(), any())).thenAnswer(invocation -> {
            Long reportId = invocation.getArgument(0);
            if (Long.valueOf(821L).equals(reportId)) {
                throw new IllegalStateException("boom");
            }
            PlanReport lineReport = mock(PlanReport.class);
            when(lineReport.getId()).thenReturn(reportId);
            return lineReport;
        });

        ReportFillPreviewResponse preview = service.preview(multi, CYCLE_ID, "202611", null, null, collegeReporter());
        ReportFillCommitResponse response = service.commit(preview.batchId(),
                new ReportFillCommitRequest(preview.confirmToken(), ""), collegeReporter());

        assertThat(response.results()).hasSize(2);
        assertThat(response.results()).anyMatch(result -> result.status() == ReportFillGroupStatus.FAILED);
        assertThat(response.results()).anyMatch(result -> result.status() == ReportFillGroupStatus.SUCCESS);
        assertThat(response.summary().failed()).isEqualTo(1);
        assertThat(response.summary().success()).isEqualTo(1);
    }

    @Test
    @DisplayName("commit：确认令牌不匹配 → 拒绝")
    void commitRejectsWrongConfirmToken() {
        MockMultipartFile one = file("1|计算机学院|什邡管委会|指标A|40|正常|说明");
        givenLine(44L, "指标A");

        ReportFillPreviewResponse preview = service.preview(one, CYCLE_ID, "202611", null, null, collegeReporter());

        assertThatThrownBy(() -> service.commit(preview.batchId(),
                new ReportFillCommitRequest("wrong-token", ""), collegeReporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确认令牌");
    }

    @Test
    @DisplayName("非学院账号 → 拒绝使用导入式上报")
    void nonCollegeUserIsRejected() {
        CurrentUser functionalReporter = new CurrentUser(9002L, "jiaowu_report", "教务处填报人", "f@example.com",
                44L, List.of());
        when(organizationRepository.findById(44L)).thenReturn(Optional.of(functionalDept));

        MockMultipartFile one = file("1|计算机学院|什邡管委会|指标A|40|正常|说明");

        assertThatThrownBy(() -> service.preview(one, CYCLE_ID, "202611", null, null, functionalReporter))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("二级学院填报人");
    }

    @Test
    @DisplayName("归口职能部门名查不到 → 组级错误点名")
    void unknownFunctionalDeptIsGroupError() {
        MockMultipartFile orphan = file("1|计算机学院|不存在的部门|指标A|40|正常|说明");

        ReportFillPreviewResponse preview = service.preview(orphan, CYCLE_ID, "202611", null, null, collegeReporter());

        assertThat(preview.groups().get(0).groupError()).contains("找不到归口职能部门");
        assertThat(preview.blocking()).isTrue();
    }
}
