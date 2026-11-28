package com.sism.analytics.application;

import com.sism.analytics.domain.repository.DashboardSummaryQueryRepository;
import com.sism.analytics.interfaces.dto.DashboardSummaryDTO;
import com.sism.analytics.interfaces.dto.DepartmentProgressDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = DashboardSummaryCachingTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DashboardSummaryCachingTest {

    @Autowired
    private DashboardSummaryService dashboardSummaryService;

    @Autowired
    private DashboardSummaryQueryRepository dashboardSummaryQueryRepository;

    @Test
    void dashboardSummaryShouldBeCachedAcrossRepeatedCalls() {
        when(dashboardSummaryQueryRepository.fetchDashboardSummaryMetrics(null))
                .thenReturn(new DashboardSummaryQueryRepository.DashboardSummaryMetrics(
                        10L, 4L, 60.0, 70.0, 50.0, 2L, 3L, 5L
                ));

        DashboardSummaryDTO first = dashboardSummaryService.getDashboardSummary(DashboardScope.full());
        DashboardSummaryDTO second = dashboardSummaryService.getDashboardSummary(DashboardScope.full());

        assertEquals(first, second);
        verify(dashboardSummaryQueryRepository, times(1)).fetchDashboardSummaryMetrics(null);
    }

    @Test
    void departmentProgressShouldBeCachedAcrossRepeatedCalls() {
        when(dashboardSummaryQueryRepository.fetchDepartmentProgressRows(null))
                .thenReturn(List.of(
                        new DashboardSummaryQueryRepository.DepartmentProgressRow("部门A", 82.36, 10, 8, 1)
                ));

        List<DepartmentProgressDTO> first = dashboardSummaryService.getDepartmentProgress(DashboardScope.full());
        List<DepartmentProgressDTO> second = dashboardSummaryService.getDepartmentProgress(DashboardScope.full());

        assertEquals(first, second);
        verify(dashboardSummaryQueryRepository, times(1)).fetchDepartmentProgressRows(null);
    }

    // ==================================================================
    // 2026-11-27 角色分流：缓存 key 必须带视角维度，不同角色不得互串数据
    // ==================================================================

    @Test
    void scopedSummariesShouldNotLeakAcrossRoles() {
        when(dashboardSummaryQueryRepository.fetchDashboardSummaryMetrics(null))
                .thenReturn(new DashboardSummaryQueryRepository.DashboardSummaryMetrics(
                        100L, 40L, 60.0, 70.0, 50.0, 2L, 3L, 5L
                ));
        when(dashboardSummaryQueryRepository.fetchDashboardSummaryMetrics(57L))
                .thenReturn(new DashboardSummaryQueryRepository.DashboardSummaryMetrics(
                        4L, 3L, 75.0, 80.0, 70.0, 0L, 1L, 0L
                ));
        when(dashboardSummaryQueryRepository.fetchDashboardSummaryMetrics(48L))
                .thenReturn(new DashboardSummaryQueryRepository.DashboardSummaryMetrics(
                        6L, 2L, 33.0, 30.0, 36.0, 1L, 0L, 0L
                ));

        DashboardSummaryDTO full = dashboardSummaryService.getDashboardSummary(DashboardScope.full());
        DashboardSummaryDTO college = dashboardSummaryService.getDashboardSummary(DashboardScope.college(57L));
        DashboardSummaryDTO functional = dashboardSummaryService.getDashboardSummary(DashboardScope.functional(48L));

        // 三个视角各自命中自己的缓存条目，数字互不串
        assertEquals(100L, full.getTotalIndicators());
        assertEquals(4L, college.getTotalIndicators());
        assertEquals(6L, functional.getTotalIndicators());
        assertNotEquals(full, college);
        assertNotEquals(college, functional);
        verify(dashboardSummaryQueryRepository, times(1)).fetchDashboardSummaryMetrics(null);
        verify(dashboardSummaryQueryRepository, times(1)).fetchDashboardSummaryMetrics(57L);
        verify(dashboardSummaryQueryRepository, times(1)).fetchDashboardSummaryMetrics(48L);
    }

    @Test
    void scopedDepartmentProgressShouldNotLeakAcrossRoles() {
        when(dashboardSummaryQueryRepository.fetchDepartmentProgressRows(null))
                .thenReturn(List.of(
                        new DashboardSummaryQueryRepository.DepartmentProgressRow("部门A", 82.36, 10, 8, 1)
                ));
        when(dashboardSummaryQueryRepository.fetchDepartmentProgressRows(57L))
                .thenReturn(List.of(
                        new DashboardSummaryQueryRepository.DepartmentProgressRow("计算机学院", 90.0, 4, 3, 0)
                ));

        List<DepartmentProgressDTO> full = dashboardSummaryService.getDepartmentProgress(DashboardScope.full());
        List<DepartmentProgressDTO> college =
                dashboardSummaryService.getDepartmentProgress(DashboardScope.college(57L));

        assertEquals(1, full.size());
        assertEquals(1, college.size());
        assertEquals("部门A", full.get(0).getDept());
        assertEquals("计算机学院", college.get(0).getDept());
        verify(dashboardSummaryQueryRepository, times(1)).fetchDepartmentProgressRows(null);
        verify(dashboardSummaryQueryRepository, times(1)).fetchDepartmentProgressRows(57L);
    }

    @Test
    void recentActivitiesShouldBeCachedPerScope() {
        when(dashboardSummaryQueryRepository.fetchRecentActivityRows(20, 57L))
                .thenReturn(List.of(
                        new DashboardSummaryQueryRepository.RecentActivityRow(1L, "指标A", "COMPLETED", 88.0, "2026-04-07T01:00:00", "计算机学院")
                ));

        List<Map<String, Object>> first =
                dashboardSummaryService.getRecentActivities(DashboardScope.college(57L));
        List<Map<String, Object>> second =
                dashboardSummaryService.getRecentActivities(DashboardScope.college(57L));

        assertEquals(first, second);
        assertEquals(1, first.size());
        verify(dashboardSummaryQueryRepository, times(1)).fetchRecentActivityRows(20, 57L);
    }

    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean("analyticsCacheManager")
        CacheManager analyticsCacheManager() {
            return new ConcurrentMapCacheManager("dashboard-summary", "department-progress", "recent-activities");
        }

        @Bean
        DashboardSummaryQueryRepository dashboardSummaryQueryRepository() {
            return mock(DashboardSummaryQueryRepository.class);
        }

        @Bean
        DashboardSummaryService dashboardSummaryService(DashboardSummaryQueryRepository dashboardSummaryQueryRepository) {
            return new DashboardSummaryService(dashboardSummaryQueryRepository, analyticsCacheManager());
        }
    }
}
