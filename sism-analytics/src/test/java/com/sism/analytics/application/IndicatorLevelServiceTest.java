package com.sism.analytics.application;

import com.sism.analytics.domain.repository.IndicatorLevelQueryRepository;
import com.sism.analytics.domain.repository.IndicatorLevelQueryRepository.IndicatorLevelRow;
import com.sism.analytics.interfaces.dto.IndicatorLevelsResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * IndicatorLevelService 单测（2026-11-27 行级鉴定明细 + 角色校验）。
 */
class IndicatorLevelServiceTest {

    private final IndicatorLevelQueryRepository queryRepository = mock(IndicatorLevelQueryRepository.class);
    private final IndicatorLevelService service = new IndicatorLevelService(queryRepository);

    @Test
    @DisplayName("月份模式：按（组织，月份）返回行级明细，鉴定优先自评，同指标多轮上报取最后一条")
    void monthModeShouldReturnRowsWithEffectiveLevel() {
        when(queryRepository.fetchIndicatorLevels("202601", "202612", 57L)).thenReturn(List.of(
                new IndicatorLevelRow(1L, "指标1", "202611", "DELAYED", "NORMAL"),
                new IndicatorLevelRow(2L, "指标2", "202611", null, "DELAYED"),
                new IndicatorLevelRow(3L, "指标3", "202611", "AHEAD", null),
                // 同指标同月多轮上报：行序靠后的 NORMAL 覆盖靠前的 DELAYED
                new IndicatorLevelRow(4L, "指标4", "202611", "DELAYED", "DELAYED"),
                new IndicatorLevelRow(4L, "指标4", "202611", "DELAYED", "NORMAL"),
                // 其他月份行在月份模式下被过滤
                new IndicatorLevelRow(5L, "指标5", "202610", null, "AHEAD")
        ));

        IndicatorLevelsResponseDTO response = service.getIndicatorLevels(
                2026, 11, 57L, DashboardScope.full());

        assertEquals(2026, response.getYear());
        assertEquals(11, response.getMonth());
        assertEquals(57L, response.getOrgId());
        assertEquals(4, response.getItems().size());
        // 鉴定优先自评：appraisal_level 有值用鉴定，否则 self_rating 兜底
        assertEquals("NORMAL", response.getItems().get(0).getLevel());
        assertEquals("DELAYED", response.getItems().get(1).getLevel());
        assertEquals("AHEAD", response.getItems().get(2).getLevel());
        assertEquals("NORMAL", response.getItems().get(3).getLevel());
    }

    @Test
    @DisplayName("年度模式（month=null）：返回全年各月行，月份透传行数据")
    void yearModeShouldReturnAllMonths() {
        when(queryRepository.fetchIndicatorLevels("202601", "202612", null)).thenReturn(List.of(
                new IndicatorLevelRow(1L, "指标1", "202610", null, "AHEAD"),
                new IndicatorLevelRow(2L, "指标2", "202611", "DELAYED", null)
        ));

        IndicatorLevelsResponseDTO response = service.getIndicatorLevels(2026, null, null, DashboardScope.full());

        assertNull(response.getMonth());
        assertEquals(2, response.getItems().size());
        assertEquals(10, response.getItems().get(0).getMonth());
        assertEquals(11, response.getItems().get(1).getMonth());
    }

    @Test
    @DisplayName("角色校验：学院视角强制查本院，越权查其他组织抛 403")
    void scopedUserQueryingOtherOrgShouldBeDenied() {
        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                () -> service.getIndicatorLevels(2026, 11, 58L, DashboardScope.college(57L)));
        assertTrue(denied.getMessage().contains("58"));

        // 学院视角不传 orgId → 强制为本院
        when(queryRepository.fetchIndicatorLevels("202601", "202612", 57L)).thenReturn(List.of());
        service.getIndicatorLevels(2026, 11, null, DashboardScope.college(57L));
        service.getIndicatorLevels(2026, 11, 57L, DashboardScope.college(57L));

        // 职能部门视角同理
        service.getIndicatorLevels(2026, 11, 66L, DashboardScope.functional(66L));

        verify(queryRepository, times(2)).fetchIndicatorLevels("202601", "202612", 57L);
        verify(queryRepository).fetchIndicatorLevels("202601", "202612", 66L);
        verifyNoMoreInteractions(queryRepository);
    }
}
