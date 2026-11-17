package com.sism.analytics.application;

import com.sism.analytics.domain.repository.CollegeLevelQueryRepository;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeDispatchCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelMonthlyCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeReportedMonthRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.DelayedIndicatorRow;
import com.sism.analytics.interfaces.dto.CollegeLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO;
import com.sism.analytics.interfaces.dto.DelayedIndicatorsResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CollegeLevelDashboardServiceTest {

    private final CollegeLevelQueryRepository queryRepository = mock(CollegeLevelQueryRepository.class);
    private final CollegeLevelDashboardService service = new CollegeLevelDashboardService(queryRepository);

    @Test
    @DisplayName("getCollegeLevels 应把 yyyyMM 月份与聚合行映射为契约 DTO")
    void getCollegeLevelsShouldMapRowsToContractDto() {
        when(queryRepository.fetchCollegeDispatchCounts(2026)).thenReturn(List.of(
                new CollegeDispatchCountRow(57L, "计算机学院", 5, 2)
        ));
        when(queryRepository.fetchCollegeReportedMonths("202610", "202610")).thenReturn(List.of(
                new CollegeReportedMonthRow(10, 57L, "计算机学院")
        ));
        when(queryRepository.fetchCollegeLevelCounts("202610")).thenReturn(List.of(
                new CollegeLevelCountRow(57L, "计算机学院", 3, 1, 0, 4)
        ));

        CollegeLevelsResponseDTO response = service.getCollegeLevels(2026, 10);

        assertEquals(2026, response.getYear());
        assertEquals(10, response.getMonth());
        assertEquals(1, response.getColleges().size());
        assertEquals(57L, response.getColleges().get(0).getOrgId());
        assertEquals("计算机学院", response.getColleges().get(0).getOrgName());
        assertEquals(3L, response.getColleges().get(0).getAhead());
        assertEquals(1L, response.getColleges().get(0).getNormal());
        assertEquals(0L, response.getColleges().get(0).getDelayed());
        assertEquals(4L, response.getColleges().get(0).getTotal());
        assertEquals(5L, response.getColleges().get(0).getDispatchedCount());
        assertEquals(2L, response.getColleges().get(0).getUndispatchedCount());
        assertTrue(response.getColleges().get(0).isReported());
        verify(queryRepository).fetchCollegeDispatchCounts(2026);
        verify(queryRepository).fetchCollegeReportedMonths("202610", "202610");
        verify(queryRepository).fetchCollegeLevelCounts("202610");
    }

    @Test
    @DisplayName("未填报学院（当年有指标但当月无已提交/已审批月报）应返回灰柱数据：reported=false、等级计数为 0、下发数照实")
    void getCollegeLevelsShouldReturnUnreportedCollegeForGreyBar() {
        // 57 计算机学院已上报；58 电子学院有下发指标但当月只报了 DRAFT（DRAFT 不进 reported 集合）；
        // 59 外语学院只在 reported 集合出现（无当年指标但有历史已上报数据）
        when(queryRepository.fetchCollegeDispatchCounts(2026)).thenReturn(List.of(
                new CollegeDispatchCountRow(57L, "计算机学院", 5, 2),
                new CollegeDispatchCountRow(58L, "电子学院", 3, 1)
        ));
        when(queryRepository.fetchCollegeReportedMonths("202610", "202610")).thenReturn(List.of(
                new CollegeReportedMonthRow(10, 57L, "计算机学院"),
                new CollegeReportedMonthRow(10, 59L, "外语学院")
        ));
        when(queryRepository.fetchCollegeLevelCounts("202610")).thenReturn(List.of(
                new CollegeLevelCountRow(57L, "计算机学院", 3, 1, 1, 5)
        ));

        CollegeLevelsResponseDTO response = service.getCollegeLevels(2026, 10);

        assertEquals(3, response.getColleges().size());
        // TreeSet 按 orgId 升序：57、58、59
        var unreported = response.getColleges().get(1);
        assertEquals(58L, unreported.getOrgId());
        assertEquals("电子学院", unreported.getOrgName());
        assertFalse(unreported.isReported());
        assertEquals(0L, unreported.getAhead());
        assertEquals(0L, unreported.getNormal());
        assertEquals(0L, unreported.getDelayed());
        assertEquals(0L, unreported.getTotal());
        assertEquals(3L, unreported.getDispatchedCount());
        assertEquals(1L, unreported.getUndispatchedCount());

        // 只有已上报记录、无当年指标的学院：学院名来自 reported 行，下发数为 0
        var reportedOnly = response.getColleges().get(2);
        assertEquals(59L, reportedOnly.getOrgId());
        assertEquals("外语学院", reportedOnly.getOrgName());
        assertTrue(reportedOnly.isReported());
        assertEquals(0L, reportedOnly.getDispatchedCount());
    }

    @Test
    @DisplayName("getCollegeLevels 月份个位数应补零为 yyyyMM（如 2026-03 → 202603）")
    void getCollegeLevelsShouldFormatSingleDigitMonth() {
        when(queryRepository.fetchCollegeDispatchCounts(2026)).thenReturn(List.of());
        when(queryRepository.fetchCollegeReportedMonths("202603", "202603")).thenReturn(List.of());
        when(queryRepository.fetchCollegeLevelCounts("202603")).thenReturn(List.of());

        CollegeLevelsResponseDTO response = service.getCollegeLevels(2026, 3);

        assertEquals(2026, response.getYear());
        assertEquals(3, response.getMonth());
        assertTrue(response.getColleges().isEmpty());
        verify(queryRepository).fetchCollegeLevelCounts("202603");
    }

    @Test
    @DisplayName("getCollegeLevelsTrend 应固定返回 1-12 月全量且未填报学院每月都在（灰柱督办）")
    void getCollegeLevelsTrendShouldReturnAllTwelveMonthsWithUnreportedColleges() {
        when(queryRepository.fetchCollegeDispatchCounts(2026)).thenReturn(List.of(
                new CollegeDispatchCountRow(57L, "计算机学院", 4, 0),
                new CollegeDispatchCountRow(58L, "电子学院", 1, 0)
        ));
        // 10 月：57 已上报；12 月：58 已上报（57 未报）
        when(queryRepository.fetchCollegeReportedMonths("202601", "202612")).thenReturn(List.of(
                new CollegeReportedMonthRow(10, 57L, "计算机学院"),
                new CollegeReportedMonthRow(12, 58L, "电子学院")
        ));
        when(queryRepository.fetchCollegeLevelMonthlyCounts("202601", "202612")).thenReturn(List.of(
                new CollegeLevelMonthlyCountRow(12, 57L, "计算机学院", 1, 0, 0, 1),
                new CollegeLevelMonthlyCountRow(10, 57L, "计算机学院", 3, 1, 0, 4),
                new CollegeLevelMonthlyCountRow(12, 58L, "电子学院", 0, 0, 2, 2)
        ));

        CollegeLevelTrendResponseDTO response = service.getCollegeLevelsTrend(2026);

        assertEquals(2026, response.getYear());
        assertEquals(12, response.getMonths().size());
        // 月份升序且全量 1..12
        for (int m = 1; m <= 12; m++) {
            assertEquals(m, response.getMonths().get(m - 1).getMonth());
        }
        // 10 月：57 已上报（计数 3/1/0），58 未填报但有下发指标 → 灰柱行
        var october = response.getMonths().get(9);
        assertEquals(2, october.getColleges().size());
        var oct57 = october.getColleges().get(0);
        assertEquals(57L, oct57.getOrgId());
        assertTrue(oct57.isReported());
        assertEquals(3L, oct57.getAhead());
        assertEquals(10, oct57.getMonth());
        var oct58 = october.getColleges().get(1);
        assertEquals(58L, oct58.getOrgId());
        assertFalse(oct58.isReported());
        assertEquals(0L, oct58.getTotal());
        // 12 月：58 已上报（DELAYED 2），57 未填报灰柱
        var december = response.getMonths().get(11);
        assertEquals(2, december.getColleges().size());
        var dec58 = december.getColleges().get(1);
        assertTrue(dec58.isReported());
        assertEquals(2L, dec58.getDelayed());
        assertEquals(12, dec58.getMonth());
        // 11 月：两个学院都未填报但有下发指标 → 均出现灰柱行
        var november = response.getMonths().get(10);
        assertEquals(2, november.getColleges().size());
        assertFalse(november.getColleges().get(0).isReported());
        assertFalse(november.getColleges().get(1).isReported());
        verify(queryRepository).fetchCollegeLevelMonthlyCounts("202601", "202612");
    }

    @Test
    @DisplayName("getDelayedIndicators 应按指标ID去重并透传等级与来源部门")
    void getDelayedIndicatorsShouldDedupeByIndicatorId() {
        when(queryRepository.fetchDelayedIndicators("202610")).thenReturn(List.of(
                new DelayedIndicatorRow(101L, "指标A", 57L, "计算机学院", "战略发展部", "DELAYED", "DELAYED"),
                new DelayedIndicatorRow(101L, "指标A", 57L, "计算机学院", "战略发展部", "DELAYED", null),
                new DelayedIndicatorRow(102L, "指标B", 58L, "电子学院", null, null, "DELAYED")
        ));

        DelayedIndicatorsResponseDTO response = service.getDelayedIndicators(2026, 10);

        assertEquals(2026, response.getYear());
        assertEquals(10, response.getMonth());
        assertEquals(2, response.getItems().size());
        assertEquals(101L, response.getItems().get(0).getIndicatorId());
        assertEquals("计算机学院", response.getItems().get(0).getOrgName());
        assertEquals("战略发展部", response.getItems().get(0).getOwnerOrgName());
        assertEquals("DELAYED", response.getItems().get(0).getAppraisalLevel());
        assertEquals(102L, response.getItems().get(1).getIndicatorId());
        assertEquals(null, response.getItems().get(1).getSelfRating());
        assertEquals("DELAYED", response.getItems().get(1).getAppraisalLevel());
        verify(queryRepository).fetchDelayedIndicators("202610");
    }

    @Test
    @DisplayName("year 缺失或越界应抛出说人话的参数错误")
    void getCollegeLevelsShouldRejectInvalidYear() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.getCollegeLevels(null, 10));
        assertTrue(missing.getMessage().contains("year"));

        for (int invalid : new int[]{-1, 1999, 3000, 99999}) {
            assertThrows(IllegalArgumentException.class, () -> service.getCollegeLevels(invalid, 10));
        }

        IllegalArgumentException missingTrend = assertThrows(IllegalArgumentException.class,
                () -> service.getCollegeLevelsTrend(null));
        assertTrue(missingTrend.getMessage().contains("year"));

        IllegalArgumentException outOfRangeTrend = assertThrows(IllegalArgumentException.class,
                () -> service.getCollegeLevelsTrend(3000));
        assertTrue(outOfRangeTrend.getMessage().contains("2000-2999"));

        IllegalArgumentException missingDelayed = assertThrows(IllegalArgumentException.class,
                () -> service.getDelayedIndicators(null, 10));
        assertTrue(missingDelayed.getMessage().contains("year"));

        verifyNoInteractions(queryRepository);
    }

    @Test
    @DisplayName("month 缺失或越界应抛出说人话的参数错误")
    void getCollegeLevelsShouldRejectInvalidMonth() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.getCollegeLevels(2026, null));
        assertTrue(missing.getMessage().contains("month"));

        for (int invalid : new int[]{0, 13, -5}) {
            assertThrows(IllegalArgumentException.class, () -> service.getCollegeLevels(2026, invalid));
        }

        IllegalArgumentException outOfRange = assertThrows(IllegalArgumentException.class,
                () -> service.getCollegeLevels(2026, 13));
        assertTrue(outOfRange.getMessage().contains("1-12"));

        IllegalArgumentException missingDelayedMonth = assertThrows(IllegalArgumentException.class,
                () -> service.getDelayedIndicators(2026, null));
        assertTrue(missingDelayedMonth.getMessage().contains("month"));

        verifyNoInteractions(queryRepository);
    }
}
