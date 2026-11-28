package com.sism.analytics.application;

import com.sism.analytics.domain.repository.DashboardSummaryQueryRepository;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DashboardSummaryServiceTest {

    @Test
    @DisplayName("getDashboardSummary should use canonical alert severities")
    void getDashboardSummaryShouldUseCanonicalAlertSeverities() {
        DashboardSummaryQueryRepository queryRepository = mock(DashboardSummaryQueryRepository.class);
        DashboardSummaryService service = new DashboardSummaryService(queryRepository, new ConcurrentMapCacheManager("dashboard-summary", "department-progress", "recent-activities"));

        when(queryRepository.fetchDashboardSummaryMetrics(null)).thenReturn(
                new DashboardSummaryQueryRepository.DashboardSummaryMetrics(
                        10L, 4L, 60.0, 70.0, 50.0, 2L, 3L, 5L
                )
        );

        var result = service.getDashboardSummary(DashboardScope.full());

        assertEquals(2L, result.getAlertIndicators().getSevere());
        assertEquals(3L, result.getAlertIndicators().getModerate());
        assertEquals(5L, result.getAlertIndicators().getNormal());
        assertEquals(10L, result.getWarningCount());
        verify(queryRepository).fetchDashboardSummaryMetrics(null);
        verifyNoMoreInteractions(queryRepository);
    }

    @Test
    @DisplayName("getDepartmentProgress should map repository rows into DTOs")
    void getDepartmentProgressShouldMapRows() {
        DashboardSummaryQueryRepository queryRepository = mock(DashboardSummaryQueryRepository.class);
        DashboardSummaryService service = new DashboardSummaryService(queryRepository, new ConcurrentMapCacheManager("dashboard-summary", "department-progress", "recent-activities"));

        when(queryRepository.fetchDepartmentProgressRows(null)).thenReturn(List.of(
                new DashboardSummaryQueryRepository.DepartmentProgressRow("部门A", 82.36, 10, 8, 1),
                new DashboardSummaryQueryRepository.DepartmentProgressRow("部门B", 49.9, 5, 1, 2)
        ));

        var result = service.getDepartmentProgress(DashboardScope.full());

        assertEquals(2, result.size());
        assertEquals("on_track", result.get(0).getStatus());
        assertEquals(82.36, result.get(0).getProgress());
        assertEquals("behind", result.get(1).getStatus());
    }

    @Test
    @DisplayName("getRecentActivities should preserve repository ordering and fields")
    void getRecentActivitiesShouldMapRows() {
        DashboardSummaryQueryRepository queryRepository = mock(DashboardSummaryQueryRepository.class);
        DashboardSummaryService service = new DashboardSummaryService(queryRepository, new ConcurrentMapCacheManager("dashboard-summary", "department-progress", "recent-activities"));

        when(queryRepository.fetchRecentActivityRows(20, null)).thenReturn(List.of(
                new DashboardSummaryQueryRepository.RecentActivityRow(1L, "指标A", "COMPLETED", 88.0, "2026-04-07T01:00:00", "职能部门")
        ));

        var result = service.getRecentActivities(DashboardScope.full());

        assertEquals(1, result.size());
        assertEquals("指标A", result.get(0).get("description"));
        assertEquals("职能部门", result.get(0).get("orgName"));
    }

    // ==================================================================
    // 2026-11-27 角色分流：学院/职能部门视角按 target_org 收敛
    // ==================================================================

    @Test
    @DisplayName("学院视角：汇总统计只统计本院（target_org=本院传入查询层）")
    void collegeScopeShouldPassOwnOrgIdToMetricsQuery() {
        DashboardSummaryQueryRepository queryRepository = mock(DashboardSummaryQueryRepository.class);
        DashboardSummaryService service = new DashboardSummaryService(queryRepository, new ConcurrentMapCacheManager("dashboard-summary", "department-progress", "recent-activities"));

        when(queryRepository.fetchDashboardSummaryMetrics(57L)).thenReturn(
                new DashboardSummaryQueryRepository.DashboardSummaryMetrics(
                        4L, 3L, 75.0, 80.0, 70.0, 0L, 1L, 0L
                )
        );

        var result = service.getDashboardSummary(DashboardScope.college(57L));

        assertEquals(4L, result.getTotalIndicators());
        assertEquals(3L, result.getCompletedIndicators());
        assertEquals(1L, result.getWarningCount());
        verify(queryRepository).fetchDashboardSummaryMetrics(57L);
        verifyNoMoreInteractions(queryRepository);
    }

    @Test
    @DisplayName("职能部门视角：部门进度/最近活动只返回本部门行")
    void functionalScopeShouldPassOwnOrgIdToQueries() {
        DashboardSummaryQueryRepository queryRepository = mock(DashboardSummaryQueryRepository.class);
        DashboardSummaryService service = new DashboardSummaryService(queryRepository, new ConcurrentMapCacheManager("dashboard-summary", "department-progress", "recent-activities"));

        when(queryRepository.fetchDepartmentProgressRows(48L)).thenReturn(List.of(
                new DashboardSummaryQueryRepository.DepartmentProgressRow("就业创业指导中心", 66.0, 6, 4, 1)
        ));
        when(queryRepository.fetchRecentActivityRows(20, 48L)).thenReturn(List.of(
                new DashboardSummaryQueryRepository.RecentActivityRow(2L, "指标B", "IN_PROGRESS", 50.0, "2026-11-20T09:00:00", "就业创业指导中心")
        ));

        var progress = service.getDepartmentProgress(DashboardScope.functional(48L));
        var activities = service.getRecentActivities(DashboardScope.functional(48L));

        assertEquals(1, progress.size());
        assertEquals("就业创业指导中心", progress.get(0).getDept());
        assertEquals(1, activities.size());
        assertEquals("指标B", activities.get(0).get("description"));
        verify(queryRepository).fetchDepartmentProgressRows(48L);
        verify(queryRepository).fetchRecentActivityRows(20, 48L);
        verifyNoMoreInteractions(queryRepository);
    }

    @Test
    @DisplayName("scope 为 null 时按全量兜底（与既有行为一致）")
    void nullScopeShouldFallBackToFull() {
        DashboardSummaryQueryRepository queryRepository = mock(DashboardSummaryQueryRepository.class);
        DashboardSummaryService service = new DashboardSummaryService(queryRepository, new ConcurrentMapCacheManager("dashboard-summary", "department-progress", "recent-activities"));

        when(queryRepository.fetchDashboardSummaryMetrics(null)).thenReturn(
                new DashboardSummaryQueryRepository.DashboardSummaryMetrics(
                        10L, 4L, 60.0, 70.0, 50.0, 2L, 3L, 5L
                )
        );

        var result = service.getDashboardSummary(null);

        assertEquals(10L, result.getTotalIndicators());
        verify(queryRepository).fetchDashboardSummaryMetrics(null);
    }
}
