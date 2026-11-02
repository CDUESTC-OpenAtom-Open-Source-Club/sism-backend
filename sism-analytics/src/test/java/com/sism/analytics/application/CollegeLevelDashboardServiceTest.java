package com.sism.analytics.application;

import com.sism.analytics.domain.repository.CollegeLevelQueryRepository;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelMonthlyCountRow;
import com.sism.analytics.interfaces.dto.CollegeLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        verify(queryRepository).fetchCollegeLevelCounts("202610");
    }

    @Test
    @DisplayName("getCollegeLevels 月份个位数应补零为 yyyyMM（如 2026-03 → 202603）")
    void getCollegeLevelsShouldFormatSingleDigitMonth() {
        when(queryRepository.fetchCollegeLevelCounts("202603")).thenReturn(List.of());

        CollegeLevelsResponseDTO response = service.getCollegeLevels(2026, 3);

        assertEquals(2026, response.getYear());
        assertEquals(3, response.getMonth());
        assertTrue(response.getColleges().isEmpty());
        verify(queryRepository).fetchCollegeLevelCounts("202603");
    }

    @Test
    @DisplayName("getCollegeLevelsTrend 应按月份升序分组且区间为一月至十二月")
    void getCollegeLevelsTrendShouldGroupByMonthAscending() {
        when(queryRepository.fetchCollegeLevelMonthlyCounts("202601", "202612")).thenReturn(List.of(
                new CollegeLevelMonthlyCountRow(12, 57L, "计算机学院", 1, 0, 0, 1),
                new CollegeLevelMonthlyCountRow(10, 57L, "计算机学院", 3, 1, 0, 4),
                new CollegeLevelMonthlyCountRow(10, 58L, "电子学院", 0, 0, 2, 2)
        ));

        CollegeLevelTrendResponseDTO response = service.getCollegeLevelsTrend(2026);

        assertEquals(2026, response.getYear());
        assertEquals(2, response.getMonths().size());
        assertEquals(10, response.getMonths().get(0).getMonth());
        assertEquals(2, response.getMonths().get(0).getColleges().size());
        assertEquals(57L, response.getMonths().get(0).getColleges().get(0).getOrgId());
        assertEquals(58L, response.getMonths().get(0).getColleges().get(1).getOrgId());
        assertEquals(12, response.getMonths().get(1).getMonth());
        assertEquals(1, response.getMonths().get(1).getColleges().size());
        verify(queryRepository).fetchCollegeLevelMonthlyCounts("202601", "202612");
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

        verifyNoInteractions(queryRepository);
    }
}
