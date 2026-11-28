package com.sism.analytics.application;

import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository;
import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository.AppraisalRow;
import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository.DispatchCountRow;
import com.sism.analytics.interfaces.dto.AnnualLevelSummaryDTO;
import com.sism.analytics.interfaces.dto.AppraisalSummaryResponseDTO;
import com.sism.analytics.interfaces.dto.DepartmentLevelDTO;
import com.sism.analytics.interfaces.dto.LaggingTaskDTO;
import com.sism.analytics.interfaces.dto.MonthState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppraisalSummaryService 单测（2026-11-18 按月快照口径）。
 *
 * <p>覆盖拍板口径的四个核心场景：①快照口径（各月互不沿用，10 月与 11 月各自独立显示）
 * ②未报月 → 无鉴定值 ③审批中月 → 无鉴定值 + IN_REVIEW 标记 ④滞后清单含职能部门线。
 * 语义变化说明：旧口径「每指标取不晚于选中月的最近一次鉴定、当月未报沿用上次结论」已废除，
 * 鉴定值只来自选中月 APPROVED 月报。</p>
 */
class AppraisalSummaryServiceTest {

    private final AppraisalSummaryQueryRepository queryRepository = mock(AppraisalSummaryQueryRepository.class);
    private final AppraisalSummaryService service = new AppraisalSummaryService(queryRepository);

    /** 职能部门线月报行（reportOrgType=FUNC_DEPT，按上报组织自身归组） */
    private static AppraisalRow functionalRow(String month, long indicatorId, String level, String status) {
        return new AppraisalRow(100L, "学生处", "FUNC_DEPT", null, null, month, indicatorId, "指标" + indicatorId,
                200L, "学生处", level, new BigDecimal("10.00"), status);
    }

    /** 学院线月报行（reportOrgType=academic，无归口职能部门——旧库兼容场景） */
    private static AppraisalRow academicRow(String month, long indicatorId, String level, String status) {
        return new AppraisalRow(300L, "计算机学院", "academic", null, null, month, indicatorId, "指标" + indicatorId,
                300L, "计算机学院", level, new BigDecimal("20.00"), status);
    }

    /** 学院线月报行（带归口职能部门：functionalOrgId / functionalOrgName） */
    private static AppraisalRow academicRowForFunctionalOrg(long functionalOrgId, String functionalOrgName,
                                                            String month, long indicatorId,
                                                            String level, String status) {
        return new AppraisalRow(300L, "计算机学院", "academic", functionalOrgId, functionalOrgName,
                month, indicatorId, "指标" + indicatorId, 300L, "计算机学院", level,
                new BigDecimal("20.00"), status);
    }

    /** 职能部门线月报行（指定上报部门；reportOrgType=FUNC_DEPT，指标 target=上报部门自身） */
    private static AppraisalRow functionalRowOf(
            long orgId, String orgName, String month, long indicatorId, String level, String status) {
        return new AppraisalRow(orgId, orgName, "FUNC_DEPT", null, null, month, indicatorId,
                "指标" + indicatorId, orgId, orgName, level, new BigDecimal("10.00"), status);
    }

    /** 按 orgId 取部门行（测试数据里 orgId 唯一；找不到直接失败） */
    private static DepartmentLevelDTO deptOf(List<DepartmentLevelDTO> departments, long orgId) {
        return departments.stream().filter(d -> d.getOrgId() == orgId).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("快照口径：10 月与 11 月各自独立显示，互不沿用（2026-11-18 拍板核心回归）")
    void appraisalSummaryShouldReturnIndependentMonthlySnapshots() {
        // 10 月 APPROVED：指标1 滞后、指标2 超前；11 月 APPROVED：指标1 转正常、指标2 转滞后
        List<AppraisalRow> rows = List.of(
                functionalRow("202610", 1L, "DELAYED", "APPROVED"),
                functionalRow("202610", 2L, "AHEAD", "APPROVED"),
                functionalRow("202611", 1L, "NORMAL", "APPROVED"),
                functionalRow("202611", 2L, "DELAYED", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        // 灰柱高度：学生处当年已下发 7 项指标
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(100L, "学生处", 7L)
        ));

        AppraisalSummaryResponseDTO october = service.getAppraisalSummary(2026, 10, null);

        assertEquals(2026, october.getYear());
        assertEquals(10, october.getMonth());
        assertEquals(11, october.getLatestMonth()); // 最近有鉴定数据的月份是 11 月
        // 10 月快照：指标1 滞后 + 指标2 超前（11 月的改判不影响 10 月）
        assertEquals(1, october.getDepartments().size());
        DepartmentLevelDTO octDept = october.getDepartments().get(0);
        assertEquals(1L, octDept.getAhead());
        assertEquals(0L, octDept.getNormal());
        assertEquals(1L, octDept.getDelayed());
        assertEquals(2L, octDept.getTotal());
        assertEquals(7L, octDept.getDispatchedCount()); // 灰柱高度数据源
        assertTrue(octDept.isReported());
        assertEquals(MonthState.APPROVED, octDept.getMonthState());
        // 10 月滞后清单只有指标1；年度简报 = 10 月快照
        assertEquals(1, october.getLaggingTasks().size());
        assertEquals(1L, october.getLaggingTasks().get(0).getIndicatorId());
        assertEquals("202610", october.getLaggingTasks().get(0).getReportMonth());
        AnnualLevelSummaryDTO octAnnual = october.getAnnual();
        assertEquals(1L, octAnnual.getAhead());
        assertEquals(1L, octAnnual.getDelayed());
        assertEquals(2L, octAnnual.getTotal());
        assertEquals(50, octAnnual.getCompletionRate());
        // 1..10 月逐月独立快照：9 月无上报 → 学生处只剩灰柱底座（已下发 7，不沿用 10 月彩段）
        assertEquals(10, october.getDepartmentMonths().size());
        assertEquals(1, october.getDepartmentMonths().get(8).getDepartments().size());
        DepartmentLevelDTO sepGray = october.getDepartmentMonths().get(8).getDepartments().get(0);
        assertEquals(100L, sepGray.getOrgId());
        assertFalse(sepGray.isReported());
        assertEquals(MonthState.NOT_REPORTED, sepGray.getMonthState());
        assertEquals(7L, sepGray.getDispatchedCount());
        assertEquals(0L, sepGray.getTotal());
        assertEquals(1, october.getDepartmentMonths().get(9).getDepartments().size());

        // 11 月快照：指标1 转正常、指标2 转滞后（10 月的结论不带入 11 月）
        AppraisalSummaryResponseDTO november = service.getAppraisalSummary(2026, 11, null);
        assertEquals(1, november.getDepartments().size());
        DepartmentLevelDTO novDept = november.getDepartments().get(0);
        assertEquals(0L, novDept.getAhead());
        assertEquals(1L, novDept.getNormal());
        assertEquals(1L, novDept.getDelayed());
        assertEquals(MonthState.APPROVED, novDept.getMonthState());
        assertEquals(1, november.getLaggingTasks().size());
        assertEquals(2L, november.getLaggingTasks().get(0).getIndicatorId());
        assertEquals(1L, november.getAnnual().getNormal());
        assertEquals(1L, november.getAnnual().getDelayed());
        // 两次调用共享同一份年度明细（每次聚合内部独立计算，不跨调用缓存）
        verify(queryRepository, times(2)).fetchAppraisalRows("202601", "202612");
    }

    @Test
    @DisplayName("未报月：无鉴定值（部门列表为空），不沿用任何其他月份的结论")
    void appraisalSummaryShouldReturnEmptyForUnreportedMonth() {
        List<AppraisalRow> rows = List.of(
                functionalRow("202610", 1L, "DELAYED", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        // 11 月没人填报 → 部门分布为空（前端置灰），滞后清单/简报无鉴定值
        assertTrue(response.getDepartments().isEmpty());
        assertTrue(response.getLaggingTasks().isEmpty());
        assertEquals(0L, response.getAnnual().getTotal());
        assertEquals(0, response.getAnnual().getCompletionRate());
        assertEquals(10, response.getLatestMonth());
        // departmentMonths 覆盖 1..11，且每月只含自己月份的数据（11 月空）
        assertEquals(11, response.getDepartmentMonths().size());
        assertTrue(response.getDepartmentMonths().get(10).getDepartments().isEmpty());
        assertEquals(1, response.getDepartmentMonths().get(9).getDepartments().size());
    }

    @Test
    @DisplayName("审批中月：无鉴定值 + monthState=IN_REVIEW（含 legacy IN_REVIEW 落库别名）")
    void appraisalSummaryShouldMarkInReviewForUnapprovedReports() {
        // 11 月只报了 SUBMITTED（新落库值）与 IN_REVIEW（legacy 别名），没有 APPROVED
        List<AppraisalRow> rows = List.of(
                functionalRow("202611", 1L, null, "SUBMITTED"),
                functionalRow("202611", 2L, null, "IN_REVIEW"),
                // 10 月有 APPROVED 数据，但按快照口径不沿用到 11 月
                functionalRow("202610", 1L, "DELAYED", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        // 审批中月份灰柱高度来自当年已下发指标数（2026-11-18 验收补齐）
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(100L, "学生处", 4L),
                new DispatchCountRow(999L, "未在分布图出现的学院", 9L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        // 审批中部门仍返回（供前端置灰标「审批中」），但鉴定值全 0、灰柱高度取已下发数；
        // 纯下发部门 999 同时作为灰柱底座返回（并集）
        assertEquals(2, response.getDepartments().size());
        DepartmentLevelDTO dept = response.getDepartments().stream()
                .filter(d -> d.getOrgId() == 100L).findFirst().orElseThrow();
        assertEquals(100L, dept.getOrgId());
        assertEquals("学生处", dept.getOrgName());
        assertTrue(dept.isReported());
        assertEquals(MonthState.IN_REVIEW, dept.getMonthState());
        assertEquals(0L, dept.getAhead());
        assertEquals(0L, dept.getNormal());
        assertEquals(0L, dept.getDelayed());
        assertEquals(0L, dept.getTotal());
        assertEquals(4L, dept.getDispatchedCount());
        DepartmentLevelDTO grayDept = response.getDepartments().stream()
                .filter(d -> d.getOrgId() == 999L).findFirst().orElseThrow();
        assertFalse(grayDept.isReported());
        assertEquals(MonthState.NOT_REPORTED, grayDept.getMonthState());
        assertEquals(9L, grayDept.getDispatchedCount());
        assertEquals(0L, grayDept.getTotal());
        // 审批中月份无鉴定值：滞后清单与简报为空，latestMonth 指向最近有鉴定数据的 10 月
        assertTrue(response.getLaggingTasks().isEmpty());
        assertEquals(0L, response.getAnnual().getTotal());
        assertEquals(10, response.getLatestMonth());
    }

    @Test
    @DisplayName("部门行口径（2026-11-27 拍板）：全量视角下学院线月报不再并入归口部门行；无归口学院行跳过")
    void academicRowsShouldNotBeMergedIntoFunctionalOrgInFullScope() {
        List<AppraisalRow> rows = List.of(
                // 学院月报：3 项指标归口预算部，AHEAD/NORMAL/DELAYED 各一
                academicRowForFunctionalOrg(63L, "预算部", "202611", 1L, "AHEAD", "APPROVED"),
                academicRowForFunctionalOrg(63L, "预算部", "202611", 2L, "NORMAL", "APPROVED"),
                academicRowForFunctionalOrg(63L, "预算部", "202611", 3L, "DELAYED", "APPROVED"),
                // 另一学院月报归口产教融合处
                academicRowForFunctionalOrg(88L, "产教融合处", "202611", 4L, "DELAYED", "APPROVED"),
                // 学院月报审批中行
                academicRowForFunctionalOrg(63L, "预算部", "202611", 5L, null, "SUBMITTED"),
                // 旧库学院行 functionalOrgId=null
                academicRow("202611", 6L, "DELAYED", "APPROVED"),
                // 职能部门月报仍按上报组织自身归组
                functionalRow("202611", 7L, "NORMAL", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(63L, "预算部", 7L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        // 预算部（只剩灰柱底座）+ 学生处（职能部门自身）；产教融合处没有自身上报行也没有下发计数，不出现；
        // 学院自身 300L 与无归口行都不出现
        assertEquals(2, response.getDepartments().size());
        assertTrue(response.getDepartments().stream().noneMatch(d -> d.getOrgId() == 300L));
        assertTrue(response.getDepartments().stream().noneMatch(d -> d.getOrgId() == 88L));
        DepartmentLevelDTO budget = deptOf(response.getDepartments(), 63L);
        assertEquals("预算部", budget.getOrgName());
        // 归口到预算部的学院线（含审批中行）不算预算部自己的上报
        assertFalse(budget.isReported());
        assertEquals(MonthState.NOT_REPORTED, budget.getMonthState());
        assertEquals(0L, budget.getAhead());
        assertEquals(0L, budget.getNormal());
        assertEquals(0L, budget.getDelayed());
        assertEquals(0L, budget.getTotal());
        assertEquals(7L, budget.getDispatchedCount()); // 灰柱高度仍取本部门已下发数
        DepartmentLevelDTO studentAffairs = deptOf(response.getDepartments(), 100L);
        assertEquals("学生处", studentAffairs.getOrgName());
        assertTrue(studentAffairs.isReported());
        assertEquals(MonthState.APPROVED, studentAffairs.getMonthState());
        assertEquals(1L, studentAffairs.getNormal());
        assertEquals(0L, studentAffairs.getAhead());
        assertEquals(0L, studentAffairs.getDelayed());
        assertEquals(1L, studentAffairs.getTotal());
        // 只有 11 月有上报：1-10 月逐月快照只有灰柱底座（预算部已下发 7），不沿用 11 月彩段
        assertEquals(11, response.getDepartmentMonths().size());
        assertEquals(1, response.getDepartmentMonths().get(9).getDepartments().size());
        DepartmentLevelDTO octGray = deptOf(response.getDepartmentMonths().get(9).getDepartments(), 63L);
        assertFalse(octGray.isReported());
        assertEquals(MonthState.NOT_REPORTED, octGray.getMonthState());
        assertEquals(7L, octGray.getDispatchedCount());
        assertEquals(2, response.getDepartmentMonths().get(10).getDepartments().size());
        // 简报/滞后清单口径不变：仍是全部 APPROVED 行（含学院线）
        assertEquals(1L, response.getAnnual().getAhead());
        assertEquals(2L, response.getAnnual().getNormal());
        assertEquals(3L, response.getAnnual().getDelayed());
        assertEquals(6L, response.getAnnual().getTotal());
        assertEquals(3, response.getLaggingTasks().size());
    }

    @Test
    @DisplayName("部门行口径（2026-11-27 拍板）：学院线归口部门与职能部门同 ID 时不再合并，部门行只含自身上报行")
    void collegeLineAndFunctionalRowsShouldNotBeMergedInFullScope() {
        List<AppraisalRow> rows = List.of(
                // 学院月报归口学生处（functionalOrgId=100，与职能部门上报组织同 ID）
                academicRowForFunctionalOrg(100L, "学生处", "202611", 1L, "AHEAD", "APPROVED"),
                functionalRow("202611", 2L, "DELAYED", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(100L, "学生处", 9L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        assertEquals(1, response.getDepartments().size());
        DepartmentLevelDTO dept = response.getDepartments().get(0);
        assertEquals(100L, dept.getOrgId());
        assertEquals(0L, dept.getAhead()); // 学院线的 AHEAD 不再计入部门行
        assertEquals(0L, dept.getNormal());
        assertEquals(1L, dept.getDelayed());
        assertEquals(1L, dept.getTotal());
        assertEquals(9L, dept.getDispatchedCount());
        // 简报口径不变：仍统计全部行
        assertEquals(2L, response.getAnnual().getTotal());
    }

    @Test
    @DisplayName("B2 修复：无归口的学院月报单独出现时部门分布为空（旧库兼容，不凭空归组）")
    void academicRowsWithoutFunctionalOrgShouldBeSkipped() {
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(List.of(
                academicRow("202611", 1L, "DELAYED", "APPROVED"),
                academicRow("202611", 2L, "AHEAD", "SUBMITTED")
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        assertTrue(response.getDepartments().isEmpty());
        // 滞后清单/简报仍按既有口径取 APPROVED 学院行（归组键只影响部门分布）
        assertEquals(1, response.getLaggingTasks().size());
        assertEquals(1L, response.getAnnual().getDelayed());
    }

    @Test
    @DisplayName("滞后清单含职能部门线与学院线（不设上报组织类型过滤，责任主体取指标 target 组织）")
    void laggingTasksShouldCoverFunctionalAndAcademicLines() {
        List<AppraisalRow> rows = List.of(
                // 职能部门线：指标 target=职能部门（学生处自报自管指标）
                functionalRow("202611", 1L, "DELAYED", "APPROVED"),
                // 学院线：指标 target=学院
                academicRow("202611", 2L, "DELAYED", "APPROVED"),
                // APPROVED 但非 DELAYED 不进清单
                academicRow("202611", 3L, "NORMAL", "APPROVED"),
                // 审批中的 DELAYED 不进清单（无鉴定值）
                academicRow("202611", 4L, "DELAYED", "SUBMITTED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        assertEquals(2, response.getLaggingTasks().size());
        // 权重 20 > 10，学院线指标2（权重 20）排前，职能部门线指标1（权重 10）排后
        LaggingTaskDTO academic = response.getLaggingTasks().get(0);
        assertEquals(2L, academic.getIndicatorId());
        assertEquals(300L, academic.getOrgId());
        assertEquals("计算机学院", academic.getOrgName());
        LaggingTaskDTO functional = response.getLaggingTasks().get(1);
        assertEquals(1L, functional.getIndicatorId());
        assertEquals(200L, functional.getOrgId());
        assertEquals("学生处", functional.getOrgName());
    }

    @Test
    @DisplayName("latestMonth 只认 APPROVED 且等级非空的月份（审批中/未鉴定月份不作为跳月目标）")
    void latestMonthShouldPointToLatestAppraisedMonth() {
        List<AppraisalRow> rows = List.of(
                // 12 月只有审批中 → 不算有鉴定数据
                functionalRow("202612", 1L, null, "SUBMITTED"),
                // 11 月 APPROVED 但全部未鉴定（等级 NULL）→ 不算有鉴定数据
                functionalRow("202611", 1L, null, "APPROVED"),
                // 10 月 APPROVED 且有鉴定 → 跳月目标
                functionalRow("202610", 1L, "NORMAL", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 12, null);

        assertEquals(10, response.getLatestMonth());
    }

    @Test
    @DisplayName("同月多轮上报时按行序取最后一条（去重）")
    void sameMonthDuplicateReportsShouldKeepLastRow() {
        List<AppraisalRow> rows = List.of(
                functionalRow("202610", 1L, "DELAYED", "APPROVED"),
                functionalRow("202610", 1L, "NORMAL", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 10, null);

        // 指标1 以行序靠后的 NORMAL 为准；滞后清单为空
        assertEquals(0L, response.getDepartments().get(0).getDelayed());
        assertEquals(1L, response.getDepartments().get(0).getNormal());
        assertTrue(response.getLaggingTasks().isEmpty());
    }

    @Test
    @DisplayName("limit 缺省为 5、上限 50；小于 1 抛出说人话的参数错误")
    void laggingLimitShouldBeNormalized() {
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(List.of());
        assertEquals(2026, service.getAppraisalSummary(2026, 10, null).getYear());

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> service.getAppraisalSummary(2026, 10, 0));
        assertTrue(invalid.getMessage().contains("limit"));

        IllegalArgumentException missingYear = assertThrows(IllegalArgumentException.class,
                () -> service.getAppraisalSummary(null, 10, null));
        assertTrue(missingYear.getMessage().contains("year"));

        IllegalArgumentException missingMonth = assertThrows(IllegalArgumentException.class,
                () -> service.getAppraisalSummary(2026, null, null));
        assertTrue(missingMonth.getMessage().contains("month"));
    }

    @Test
    @DisplayName("B2 灰柱底座：纯下发部门（当月无任何上报行）返回灰柱——reported=false / NOT_REPORTED / "
            + "高度=已下发数 / 三档全 0")
    void dispatchOnlyDepartmentsShouldProduceGrayColumns() {
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(List.of());
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(61L, "航空学院", 4L),
                new DispatchCountRow(100L, "学生处", 7L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        assertEquals(2, response.getDepartments().size());
        DepartmentLevelDTO aviation = response.getDepartments().stream()
                .filter(d -> d.getOrgId() == 61L).findFirst().orElseThrow();
        assertEquals("航空学院", aviation.getOrgName()); // 部门名取自下发计数行
        assertFalse(aviation.isReported());
        assertEquals(MonthState.NOT_REPORTED, aviation.getMonthState());
        assertEquals(4L, aviation.getDispatchedCount());
        assertEquals(0L, aviation.getAhead());
        assertEquals(0L, aviation.getNormal());
        assertEquals(0L, aviation.getDelayed());
        assertEquals(0L, aviation.getTotal());
        // 无鉴定值：滞后清单/简报为空
        assertTrue(response.getLaggingTasks().isEmpty());
        assertEquals(0L, response.getAnnual().getTotal());
        // 灰柱底座对 1..11 月逐月快照均生效
        assertEquals(11, response.getDepartmentMonths().size());
        assertTrue(response.getDepartmentMonths().stream()
                .allMatch(m -> m.getDepartments().size() == 2));
    }

    @Test
    @DisplayName("B2 灰柱底座：同月已上报部门（彩段+高度）与纯下发部门（灰柱）并存，同一部门不重复建行")
    void reportedAndDispatchOnlyDepartmentsShouldCoexist() {
        List<AppraisalRow> rows = List.of(
                // 预算部自身上报（2026-11-27 起部门行只认部门自己上报的月报）
                functionalRowOf(63L, "预算部", "202611", 1L, "AHEAD", "APPROVED"),
                functionalRowOf(63L, "预算部", "202611", 2L, "NORMAL", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(63L, "预算部", 9L),  // 已上报部门：已下发 9、已鉴定 2
                new DispatchCountRow(61L, "航空学院", 4L) // 纯下发部门：灰柱
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        assertEquals(2, response.getDepartments().size());
        DepartmentLevelDTO budget = response.getDepartments().stream()
                .filter(d -> d.getOrgId() == 63L).findFirst().orElseThrow();
        assertTrue(budget.isReported());
        assertEquals(MonthState.APPROVED, budget.getMonthState());
        assertEquals(1L, budget.getAhead());
        assertEquals(1L, budget.getNormal());
        assertEquals(0L, budget.getDelayed());
        assertEquals(2L, budget.getTotal());
        assertEquals(9L, budget.getDispatchedCount()); // 灰柱高度与彩段并存于同一行
        DepartmentLevelDTO aviation = response.getDepartments().stream()
                .filter(d -> d.getOrgId() == 61L).findFirst().orElseThrow();
        assertFalse(aviation.isReported());
        assertEquals(MonthState.NOT_REPORTED, aviation.getMonthState());
        assertEquals(4L, aviation.getDispatchedCount());
        assertEquals(0L, aviation.getTotal());
    }

    @Test
    @DisplayName("B2 灰柱底座：审批中部门仍为 IN_REVIEW 灰柱（不被并集改判 NOT_REPORTED），高度=已下发数")
    void inReviewDepartmentShouldStayGrayWithDispatchHeight() {
        List<AppraisalRow> rows = List.of(
                // 预算部自身上报、审批中（2026-11-27 起部门行只认部门自己上报的月报）
                functionalRowOf(63L, "预算部", "202611", 1L, null, "SUBMITTED"),
                functionalRowOf(63L, "预算部", "202611", 2L, null, "IN_REVIEW")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(63L, "预算部", 6L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        assertEquals(1, response.getDepartments().size());
        DepartmentLevelDTO budget = response.getDepartments().get(0);
        assertTrue(budget.isReported()); // 有上报行 → reported=true，区别于纯下发灰柱
        assertEquals(MonthState.IN_REVIEW, budget.getMonthState());
        assertEquals(6L, budget.getDispatchedCount());
        assertEquals(0L, budget.getAhead());
        assertEquals(0L, budget.getNormal());
        assertEquals(0L, budget.getDelayed());
        assertEquals(0L, budget.getTotal());
    }

    // ==================================================================
    // 2026-11-27 角色分流（看板数据视角）
    // ==================================================================

    @Test
    @DisplayName("学院视角：annual/滞后清单只统计本院（target=本院），departments 只含本院归口部门，"
            + "不并入全校灰柱底座")
    void collegeScopeShouldLimitToOwnIndicators() {
        List<AppraisalRow> rows = List.of(
                // 本院（计算机学院 300）自报行：归口预算部 2 项 + 无归口 1 项
                academicRowForFunctionalOrg(63L, "预算部", "202611", 1L, "AHEAD", "APPROVED"),
                academicRowForFunctionalOrg(63L, "预算部", "202611", 2L, "DELAYED", "APPROVED"),
                academicRow("202611", 3L, "NORMAL", "APPROVED"),
                // 其他学院（工学院 400）的行：target≠本院 → 学院视角不可见
                new AppraisalRow(400L, "工学院", "academic", 63L, "预算部", "202611", 4L, "指标4",
                        400L, "工学院", "DELAYED", new BigDecimal("20.00"), "APPROVED"),
                // 职能部门线行：target=学生处 → 学院视角不可见
                functionalRow("202611", 5L, "DELAYED", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        // 全校各归口部门的下发计数：学院视角不得混入（departments 不并灰柱底座）
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(63L, "预算部", 7L),
                new DispatchCountRow(100L, "学生处", 9L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(
                2026, 11, null, DashboardScope.college(300L));

        // 简报 = 本院 3 项（AHEAD 1 + NORMAL 1 + DELAYED 1）
        assertEquals(1L, response.getAnnual().getAhead());
        assertEquals(1L, response.getAnnual().getNormal());
        assertEquals(1L, response.getAnnual().getDelayed());
        assertEquals(3L, response.getAnnual().getTotal());
        // departments 只含本院归口部门（预算部），工学院/学生处不出现
        assertEquals(1, response.getDepartments().size());
        DepartmentLevelDTO budget = response.getDepartments().get(0);
        assertEquals(63L, budget.getOrgId());
        assertEquals("预算部", budget.getOrgName());
        assertEquals(2L, budget.getTotal());
        // 学院视角不并全校灰柱底座
        assertTrue(response.getLaggingTasks().stream().allMatch(t -> t.getOrgId() == 300L));
        // 逐月快照同样只含本院归口部门
        assertEquals(11, response.getDepartmentMonths().size());
        assertEquals(1, response.getDepartmentMonths().get(10).getDepartments().size());
    }

    @Test
    @DisplayName("职能部门视角：简报/滞后清单只统计本部门（本部门职能线 + 归口到本部门的学院线），"
            + "departments 部门行只含本部门自身上报行，下发计数只保留本部门")
    void functionalScopeShouldLimitToOwnDepartment() {
        List<AppraisalRow> rows = List.of(
                // 本部门（学生处 100）自身上报行
                functionalRow("202611", 1L, "NORMAL", "APPROVED"),
                // 学院线归口到本部门：进简报/滞后清单，不进部门行（2026-11-27 拍板）
                academicRowForFunctionalOrg(100L, "学生处", "202611", 2L, "DELAYED", "APPROVED"),
                // 其他职能部门（预算部 63）职能线行：不可见
                new AppraisalRow(63L, "预算部", "FUNC_DEPT", null, null, "202611", 3L, "指标3",
                        63L, "预算部", "DELAYED", new BigDecimal("10.00"), "APPROVED"),
                // 学院线归口到别的部门：不可见
                academicRowForFunctionalOrg(63L, "预算部", "202611", 4L, "DELAYED", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(100L, "学生处", 5L),
                new DispatchCountRow(63L, "预算部", 7L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(
                2026, 11, null, DashboardScope.functional(100L));

        // 简报只含本部门口径的 2 项（NORMAL + DELAYED）
        assertEquals(1L, response.getAnnual().getNormal());
        assertEquals(1L, response.getAnnual().getDelayed());
        assertEquals(2L, response.getAnnual().getTotal());
        // departments 只有本部门一行：只含自身上报的 1 项（归口学院线不并入），灰柱高度只取本部门下发计数
        assertEquals(1, response.getDepartments().size());
        DepartmentLevelDTO own = response.getDepartments().get(0);
        assertEquals(100L, own.getOrgId());
        assertEquals("学生处", own.getOrgName());
        assertEquals(5L, own.getDispatchedCount());
        assertEquals(0L, own.getAhead());
        assertEquals(1L, own.getNormal());
        assertEquals(0L, own.getDelayed());
        assertEquals(1L, own.getTotal());
        assertEquals(MonthState.APPROVED, own.getMonthState());
        // 逐月快照同口径：11 月只有本部门自身的 1 项
        assertEquals(1, response.getDepartmentMonths().get(10).getDepartments().size());
        assertEquals(1L, response.getDepartmentMonths().get(10).getDepartments().get(0).getTotal());
        // 滞后清单只含本部门口径的行（指标1 自报 + 指标2 归口学院线，责任单位 100/300）
        assertEquals(1, response.getLaggingTasks().size());
        assertEquals(2L, response.getLaggingTasks().get(0).getIndicatorId());
    }

    // ==================================================================
    // 2026-11-27 部门行口径（用户拍板）：「指标完成情况分布」是部门专用卡，
    // 部门行只统计部门自己上报的指标，不并入归口到该部门的学院线指标
    // ==================================================================

    @Test
    @DisplayName("部门行口径：有归口学院线的部门只统计部门自身上报的指标（逐月快照同口径），"
            + "简报/滞后清单仍是全部行")
    void departmentWithCollegeLinesShouldOnlyCountOwnIndicatorsInFullScope() {
        List<AppraisalRow> rows = List.of(
                // 11 月：学生处（100）自身上报 正常 2 + 滞后 1
                functionalRowOf(100L, "学生处", "202611", 1L, "NORMAL", "APPROVED"),
                functionalRowOf(100L, "学生处", "202611", 2L, "NORMAL", "APPROVED"),
                functionalRowOf(100L, "学生处", "202611", 3L, "DELAYED", "APPROVED"),
                // 11 月：归口到学生处的学院线（计算机学院、工学院各自上报）正常 3 + 滞后 1
                academicRowForFunctionalOrg(100L, "学生处", "202611", 11L, "NORMAL", "APPROVED"),
                academicRowForFunctionalOrg(100L, "学生处", "202611", 12L, "NORMAL", "APPROVED"),
                academicRowForFunctionalOrg(100L, "学生处", "202611", 13L, "DELAYED", "APPROVED"),
                new AppraisalRow(400L, "工学院", "COLLEGE", 100L, "学生处", "202611", 14L, "指标14",
                        400L, "工学院", "NORMAL", new BigDecimal("20.00"), "APPROVED"),
                // 10 月：学生处自身已报 1 项，归口学院线 2 项
                functionalRowOf(100L, "学生处", "202610", 1L, "NORMAL", "APPROVED"),
                academicRowForFunctionalOrg(100L, "学生处", "202610", 11L, "DELAYED", "APPROVED"),
                academicRowForFunctionalOrg(100L, "学生处", "202610", 12L, "DELAYED", "APPROVED"),
                // 11 月：另一部门教务处（44）自身上报 1 项
                functionalRowOf(44L, "教务处", "202611", 21L, "NORMAL", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(100L, "学生处", 3L),
                new DispatchCountRow(44L, "教务处", 1L),
                new DispatchCountRow(57L, "计算机学院", 4L) // 学院灰柱底座
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        // 学生处一行 = 自身 3 项（正常 2 + 滞后 1），不含归口学院线 4 项
        DepartmentLevelDTO studentAffairs = deptOf(response.getDepartments(), 100L);
        assertEquals("学生处", studentAffairs.getOrgName());
        assertTrue(studentAffairs.isReported());
        assertEquals(MonthState.APPROVED, studentAffairs.getMonthState());
        assertEquals(0L, studentAffairs.getAhead());
        assertEquals(2L, studentAffairs.getNormal());
        assertEquals(1L, studentAffairs.getDelayed());
        assertEquals(3L, studentAffairs.getTotal());
        assertEquals(3L, studentAffairs.getDispatchedCount());
        // 教务处自身 1 项；计算机学院只有灰柱底座；学院线行不进任何部门行
        DepartmentLevelDTO academicAffairs = deptOf(response.getDepartments(), 44L);
        assertEquals(1L, academicAffairs.getTotal());
        assertEquals(1L, academicAffairs.getNormal());
        DepartmentLevelDTO collegeGray = deptOf(response.getDepartments(), 57L);
        assertFalse(collegeGray.isReported());
        assertEquals(0L, collegeGray.getTotal());
        assertEquals(4L, collegeGray.getDispatchedCount());
        assertEquals(3, response.getDepartments().size());
        assertTrue(response.getDepartments().stream().noneMatch(d -> d.getOrgId() == 300L));
        assertTrue(response.getDepartments().stream().noneMatch(d -> d.getOrgId() == 400L));
        // 10 月逐月快照同口径：学生处自身 1 项，不含归口学院线 2 项
        DepartmentLevelDTO oct = deptOf(response.getDepartmentMonths().get(9).getDepartments(), 100L);
        assertEquals(0L, oct.getAhead());
        assertEquals(1L, oct.getNormal());
        assertEquals(0L, oct.getDelayed());
        assertEquals(1L, oct.getTotal());
        // 11 月逐月快照与选中月 departments 一致
        assertEquals(3L, deptOf(response.getDepartmentMonths().get(10).getDepartments(), 100L).getTotal());
        // 简报/滞后清单口径不变：仍是全部 APPROVED 行（自身 + 学院线）
        assertEquals(6L, response.getAnnual().getNormal());
        assertEquals(2L, response.getAnnual().getDelayed());
        assertEquals(8L, response.getAnnual().getTotal());
        assertEquals(2, response.getLaggingTasks().size());
    }

    @Test
    @DisplayName("部门行口径：没有归口学院线的部门，部门行只由自身上报行决定（与学院线无关）")
    void departmentWithoutCollegeLinesShouldOnlyCountOwnIndicatorsInFullScope() {
        List<AppraisalRow> rows = List.of(
                // 党委宣传部（38）自身上报：正常 1 + 滞后 1
                functionalRowOf(38L, "党委宣传部", "202611", 1L, "NORMAL", "APPROVED"),
                functionalRowOf(38L, "党委宣传部", "202611", 2L, "DELAYED", "APPROVED"),
                // 归口到别的部门（教务处 44）的学院线：不影响宣传部
                academicRowForFunctionalOrg(44L, "教务处", "202611", 3L, "DELAYED", "APPROVED"),
                // 无归口的旧库学院行：不影响任何部门行
                academicRow("202611", 4L, "DELAYED", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(38L, "党委宣传部", 12L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(2026, 11, null);

        // 只有宣传部一行（教务处没有自身上报行也没有下发计数，不出现）
        assertEquals(1, response.getDepartments().size());
        DepartmentLevelDTO propaganda = deptOf(response.getDepartments(), 38L);
        assertEquals("党委宣传部", propaganda.getOrgName());
        assertTrue(propaganda.isReported());
        assertEquals(MonthState.APPROVED, propaganda.getMonthState());
        assertEquals(0L, propaganda.getAhead());
        assertEquals(1L, propaganda.getNormal());
        assertEquals(1L, propaganda.getDelayed());
        assertEquals(2L, propaganda.getTotal());
        assertEquals(12L, propaganda.getDispatchedCount());
        assertEquals(1, response.getDepartmentMonths().get(10).getDepartments().size());
        assertEquals(2L, deptOf(response.getDepartmentMonths().get(10).getDepartments(), 38L).getTotal());
        // 简报仍是全部行
        assertEquals(4L, response.getAnnual().getTotal());
    }

    @Test
    @DisplayName("学院视角不变：departments 仍是本院各归口线的行（学院线月报按归口部门归组），不并入下发计数灰柱")
    void collegeScopeShouldKeepGroupingCollegeLinesByFunctionalOrg() {
        List<AppraisalRow> rows = List.of(
                // 本院（计算机学院 300）自报行：归口预算部 AHEAD/NORMAL/DELAYED 各一 + 审批中一条
                academicRowForFunctionalOrg(63L, "预算部", "202611", 1L, "AHEAD", "APPROVED"),
                academicRowForFunctionalOrg(63L, "预算部", "202611", 2L, "NORMAL", "APPROVED"),
                academicRowForFunctionalOrg(63L, "预算部", "202611", 3L, "DELAYED", "APPROVED"),
                academicRowForFunctionalOrg(63L, "预算部", "202611", 5L, null, "SUBMITTED"),
                // 归口产教融合处
                academicRowForFunctionalOrg(88L, "产教融合处", "202611", 4L, "DELAYED", "APPROVED"),
                // 无归口学院行：进简报，不进 departments
                academicRow("202611", 6L, "DELAYED", "APPROVED"),
                // 其他学院（工学院 400）的行：target≠本院 → 不可见
                new AppraisalRow(400L, "工学院", "COLLEGE", 63L, "预算部", "202611", 7L, "指标7",
                        400L, "工学院", "DELAYED", new BigDecimal("20.00"), "APPROVED"),
                // 职能部门线行：target≠本院 → 不可见
                functionalRowOf(63L, "预算部", "202611", 8L, "DELAYED", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        // 全校下发计数：学院视角不得混入
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(63L, "预算部", 7L),
                new DispatchCountRow(100L, "学生处", 9L)
        ));

        AppraisalSummaryResponseDTO response = service.getAppraisalSummary(
                2026, 11, null, DashboardScope.college(300L));

        // departments = 本院各归口线：预算部（AHEAD/NORMAL/DELAYED 各一）+ 产教融合处（滞后一）
        assertEquals(2, response.getDepartments().size());
        DepartmentLevelDTO budget = deptOf(response.getDepartments(), 63L);
        assertEquals("预算部", budget.getOrgName());
        assertTrue(budget.isReported());
        assertEquals(MonthState.APPROVED, budget.getMonthState());
        assertEquals(1L, budget.getAhead());
        assertEquals(1L, budget.getNormal());
        assertEquals(1L, budget.getDelayed());
        assertEquals(3L, budget.getTotal());
        assertEquals(0L, budget.getDispatchedCount()); // 学院视角不并下发计数
        DepartmentLevelDTO industry = deptOf(response.getDepartments(), 88L);
        assertEquals("产教融合处", industry.getOrgName());
        assertEquals(0L, industry.getAhead());
        assertEquals(0L, industry.getNormal());
        assertEquals(1L, industry.getDelayed());
        assertEquals(1L, industry.getTotal());
        // 逐月快照同样按归口部门归组；无上报月份为空（不并灰柱底座）
        assertEquals(2, response.getDepartmentMonths().get(10).getDepartments().size());
        assertEquals(3L, deptOf(response.getDepartmentMonths().get(10).getDepartments(), 63L).getTotal());
        assertTrue(response.getDepartmentMonths().get(9).getDepartments().isEmpty());
        // 简报 = 本院 5 项（AHEAD 1 + NORMAL 1 + DELAYED 3）
        assertEquals(1L, response.getAnnual().getAhead());
        assertEquals(1L, response.getAnnual().getNormal());
        assertEquals(3L, response.getAnnual().getDelayed());
        assertEquals(5L, response.getAnnual().getTotal());
    }

    @Test
    @DisplayName("职能部门视角：本部门那一行与全校视图里本部门行完全一致（只含自身上报）")
    void functionalScopeDepartmentRowShouldMatchFullScopeRow() {
        List<AppraisalRow> rows = List.of(
                // 学生处（100）自身上报：正常 1 + 滞后 1
                functionalRowOf(100L, "学生处", "202611", 1L, "NORMAL", "APPROVED"),
                functionalRowOf(100L, "学生处", "202611", 2L, "DELAYED", "APPROVED"),
                // 归口到学生处的学院线：滞后 1
                academicRowForFunctionalOrg(100L, "学生处", "202611", 3L, "DELAYED", "APPROVED"),
                // 其他部门的行
                academicRowForFunctionalOrg(44L, "教务处", "202611", 4L, "DELAYED", "APPROVED"),
                functionalRowOf(44L, "教务处", "202611", 5L, "NORMAL", "APPROVED")
        );
        when(queryRepository.fetchAppraisalRows("202601", "202612")).thenReturn(rows);
        when(queryRepository.fetchDispatchCounts(2026)).thenReturn(List.of(
                new DispatchCountRow(100L, "学生处", 5L),
                new DispatchCountRow(44L, "教务处", 6L)
        ));

        AppraisalSummaryResponseDTO full = service.getAppraisalSummary(2026, 11, null);
        AppraisalSummaryResponseDTO functional = service.getAppraisalSummary(
                2026, 11, null, DashboardScope.functional(100L));

        // 职能部门视角只有本部门一行，且与全校视图里学生处那一行逐字段相等
        assertEquals(1, functional.getDepartments().size());
        assertEquals(deptOf(full.getDepartments(), 100L), functional.getDepartments().get(0));
        assertEquals(deptOf(full.getDepartmentMonths().get(10).getDepartments(), 100L),
                functional.getDepartmentMonths().get(10).getDepartments().get(0));
        DepartmentLevelDTO own = functional.getDepartments().get(0);
        assertEquals(0L, own.getAhead());
        assertEquals(1L, own.getNormal());
        assertEquals(1L, own.getDelayed());
        assertEquals(2L, own.getTotal());
        assertEquals(5L, own.getDispatchedCount());
        // 简报/滞后清单口径不变：自身 + 归口学院线
        assertEquals(3L, functional.getAnnual().getTotal());
        assertEquals(2L, functional.getAnnual().getDelayed());
        assertEquals(2, functional.getLaggingTasks().size());
    }
}
