package com.sism.analytics.interfaces.rest;

import com.sism.analytics.application.CollegeLevelDashboardService;
import com.sism.analytics.application.DashboardScope;
import com.sism.analytics.application.DashboardScopeResolver;
import com.sism.analytics.application.DashboardSummaryService;
import com.sism.analytics.application.IndicatorLevelService;
import com.sism.analytics.interfaces.dto.CollegeLevelDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO;
import com.sism.analytics.interfaces.dto.IndicatorLevelsResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DashboardSummaryController Tests")
class DashboardSummaryControllerTest {

    private final DashboardSummaryService dashboardSummaryService = mock(DashboardSummaryService.class);
    private final CollegeLevelDashboardService collegeLevelDashboardService = mock(CollegeLevelDashboardService.class);
    private final com.sism.analytics.application.AppraisalSummaryService appraisalSummaryService =
            mock(com.sism.analytics.application.AppraisalSummaryService.class);
    private final IndicatorLevelService indicatorLevelService = mock(IndicatorLevelService.class);
    private final DashboardScopeResolver dashboardScopeResolver = mock(DashboardScopeResolver.class);
    private final DashboardSummaryController controller = new DashboardSummaryController(
            dashboardSummaryService,
            collegeLevelDashboardService,
            appraisalSummaryService,
            indicatorLevelService,
            dashboardScopeResolver);

    @Test
    @DisplayName("getCollegeLevels should delegate year/month/scope to service and wrap into ApiResponse")
    void getCollegeLevelsShouldDelegateToService() {
        when(dashboardScopeResolver.resolve(any())).thenReturn(DashboardScope.full());
        when(collegeLevelDashboardService.getCollegeLevels(2026, 10, DashboardScope.full())).thenReturn(
                CollegeLevelsResponseDTO.builder()
                        .year(2026)
                        .month(10)
                        .colleges(List.of(CollegeLevelDTO.builder()
                                .orgId(57L)
                                .orgName("计算机学院")
                                .ahead(3)
                                .normal(1)
                                .delayed(0)
                                .total(4)
                                .build()))
                        .build()
        );

        ResponseEntity<?> response = controller.getCollegeLevels(null, 2026, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.sism.common.ApiResponse<?>) response.getBody();
        assertEquals(200, body.getCode());
        CollegeLevelsResponseDTO data = (CollegeLevelsResponseDTO) body.getData();
        assertEquals(2026, data.getYear());
        assertEquals(10, data.getMonth());
        assertEquals(1, data.getColleges().size());
        assertEquals(57L, data.getColleges().get(0).getOrgId());
        verify(collegeLevelDashboardService).getCollegeLevels(2026, 10, DashboardScope.full());
    }

    @Test
    @DisplayName("getCollegeLevelsTrend should delegate year/scope to service and wrap into ApiResponse")
    void getCollegeLevelsTrendShouldDelegateToService() {
        when(dashboardScopeResolver.resolve(any())).thenReturn(DashboardScope.full());
        when(collegeLevelDashboardService.getCollegeLevelsTrend(2026, DashboardScope.full())).thenReturn(
                CollegeLevelTrendResponseDTO.builder()
                        .year(2026)
                        .months(List.of())
                        .build()
        );

        ResponseEntity<?> response = assertDoesNotThrow(
                () -> controller.getCollegeLevelsTrend(null, 2026));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.sism.common.ApiResponse<?>) response.getBody();
        assertEquals(200, body.getCode());
        assertEquals(2026, ((CollegeLevelTrendResponseDTO) body.getData()).getYear());
        verify(collegeLevelDashboardService).getCollegeLevelsTrend(2026, DashboardScope.full());
    }

    @Test
    @DisplayName("getIndicatorLevels should resolve scope and delegate to IndicatorLevelService")
    void getIndicatorLevelsShouldDelegateToService() {
        when(dashboardScopeResolver.resolve(any())).thenReturn(DashboardScope.full());
        when(indicatorLevelService.getIndicatorLevels(2026, 11, 57L, DashboardScope.full())).thenReturn(
                IndicatorLevelsResponseDTO.builder()
                        .year(2026)
                        .month(11)
                        .orgId(57L)
                        .items(List.of())
                        .build()
        );

        ResponseEntity<?> response = assertDoesNotThrow(
                () -> controller.getIndicatorLevels(null, 2026, 11, 57L));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.sism.common.ApiResponse<?>) response.getBody();
        assertEquals(200, body.getCode());
        IndicatorLevelsResponseDTO data = (IndicatorLevelsResponseDTO) body.getData();
        assertEquals(57L, data.getOrgId());
        verify(indicatorLevelService).getIndicatorLevels(2026, 11, 57L, DashboardScope.full());
    }

    // ==================================================================
    // 2026-11-27 角色分流：overview 三端点按当前用户视角解析并透传 service
    // ==================================================================

    @Test
    @DisplayName("getDashboardSummary should resolve scope and delegate to service")
    void getDashboardSummaryShouldResolveScopeAndDelegate() {
        when(dashboardScopeResolver.resolve(any())).thenReturn(DashboardScope.college(57L));
        when(dashboardSummaryService.getDashboardSummary(DashboardScope.college(57L))).thenReturn(
                com.sism.analytics.interfaces.dto.DashboardSummaryDTO.builder()
                        .totalScore(80.0)
                        .totalIndicators(4L)
                        .build()
        );

        ResponseEntity<?> response = assertDoesNotThrow(() -> controller.getDashboardSummary(null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.sism.common.ApiResponse<?>) response.getBody();
        assertEquals(200, body.getCode());
        assertEquals(4L, ((com.sism.analytics.interfaces.dto.DashboardSummaryDTO) body.getData()).getTotalIndicators());
        verify(dashboardSummaryService).getDashboardSummary(DashboardScope.college(57L));
    }

    @Test
    @DisplayName("getDepartmentProgress should resolve scope and delegate to service")
    void getDepartmentProgressShouldResolveScopeAndDelegate() {
        when(dashboardScopeResolver.resolve(any())).thenReturn(DashboardScope.functional(48L));
        when(dashboardSummaryService.getDepartmentProgress(DashboardScope.functional(48L))).thenReturn(List.of());

        ResponseEntity<?> response = assertDoesNotThrow(() -> controller.getDepartmentProgress(null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.sism.common.ApiResponse<?>) response.getBody();
        assertEquals(200, body.getCode());
        verify(dashboardSummaryService).getDepartmentProgress(DashboardScope.functional(48L));
    }

    @Test
    @DisplayName("getRecentActivities should resolve scope and delegate to service")
    void getRecentActivitiesShouldResolveScopeAndDelegate() {
        when(dashboardScopeResolver.resolve(any())).thenReturn(DashboardScope.full());
        when(dashboardSummaryService.getRecentActivities(DashboardScope.full())).thenReturn(List.of());

        ResponseEntity<?> response = assertDoesNotThrow(() -> controller.getRecentActivities(null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.sism.common.ApiResponse<?>) response.getBody();
        assertEquals(200, body.getCode());
        verify(dashboardSummaryService).getRecentActivities(DashboardScope.full());
    }

    @Test
    @DisplayName("all mapped endpoints should require authentication")
    void allMappedEndpointsShouldRequireAuthentication() {
        for (Method method : DashboardSummaryController.class.getDeclaredMethods()) {
            boolean mapped = method.isAnnotationPresent(GetMapping.class)
                    || method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class);
            if (!mapped) {
                continue;
            }
            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
            assertTrue(preAuthorize != null, "Missing @PreAuthorize on " + method.getName());
            assertEquals("isAuthenticated()", preAuthorize.value(), "Unexpected @PreAuthorize on " + method.getName());
        }
    }
}
