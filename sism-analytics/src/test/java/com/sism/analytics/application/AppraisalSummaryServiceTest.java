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

    /** 职能部门线月报行（reportOrgType=FUNC_DEPT） */
    private static AppraisalRow functionalRow(String month, long indicatorId, String level, String status) {
        return new AppraisalRow(100L, "学生处", "FUNC_DEPT", month, indicatorId, "指标" + indicatorId,
                200L, "学生处", level, new BigDecimal("10.00"), status);
    }

    /** 学院线月报行（reportOrgType=academic，指标 target=学院） */
    private static AppraisalRow academicRow(String month, long indicatorId, String level, String status) {
        return new AppraisalRow(300L, "计算机学院", "academic", month, indicatorId, "指标" + indicatorId,
                300L, "计算机学院", level, new BigDecimal("20.00"), status);
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
        // 1..10 月逐月独立快照：9 月无数据 → 空列表（不再沿用 8 月及之前的结论）
        assertEquals(10, october.getDepartmentMonths().size());
        assertTrue(october.getDepartmentMonths().get(8).getDepartments().isEmpty());
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

        // 审批中部门仍返回（供前端置灰标「审批中」），但鉴定值全 0、灰柱高度取已下发数
        assertEquals(1, response.getDepartments().size());
        DepartmentLevelDTO dept = response.getDepartments().get(0);
        assertEquals(100L, dept.getOrgId());
        assertEquals("学生处", dept.getOrgName());
        assertTrue(dept.isReported());
        assertEquals(MonthState.IN_REVIEW, dept.getMonthState());
        assertEquals(0L, dept.getAhead());
        assertEquals(0L, dept.getNormal());
        assertEquals(0L, dept.getDelayed());
        assertEquals(0L, dept.getTotal());
        assertEquals(4L, dept.getDispatchedCount());
        // 审批中月份无鉴定值：滞后清单与简报为空，latestMonth 指向最近有鉴定数据的 10 月
        assertTrue(response.getLaggingTasks().isEmpty());
        assertEquals(0L, response.getAnnual().getTotal());
        assertEquals(10, response.getLatestMonth());
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
}
