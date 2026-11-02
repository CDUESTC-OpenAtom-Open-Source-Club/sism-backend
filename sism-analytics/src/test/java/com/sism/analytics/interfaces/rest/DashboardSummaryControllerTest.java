package com.sism.analytics.interfaces.rest;

import com.sism.analytics.application.CollegeLevelDashboardService;
import com.sism.analytics.application.DashboardSummaryService;
import com.sism.analytics.interfaces.dto.CollegeLevelDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DashboardSummaryController Tests")
class DashboardSummaryControllerTest {

    private final DashboardSummaryService dashboardSummaryService = mock(DashboardSummaryService.class);
    private final CollegeLevelDashboardService collegeLevelDashboardService = mock(CollegeLevelDashboardService.class);
    private final DashboardSummaryController controller =
            new DashboardSummaryController(dashboardSummaryService, collegeLevelDashboardService);

    @Test
    @DisplayName("getCollegeLevels should delegate year/month to service and wrap into ApiResponse")
    void getCollegeLevelsShouldDelegateToService() {
        when(collegeLevelDashboardService.getCollegeLevels(2026, 10)).thenReturn(
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

        ResponseEntity<?> response = controller.getCollegeLevels(2026, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.sism.common.ApiResponse<?>) response.getBody();
        assertEquals(200, body.getCode());
        CollegeLevelsResponseDTO data = (CollegeLevelsResponseDTO) body.getData();
        assertEquals(2026, data.getYear());
        assertEquals(10, data.getMonth());
        assertEquals(1, data.getColleges().size());
        assertEquals(57L, data.getColleges().get(0).getOrgId());
        verify(collegeLevelDashboardService).getCollegeLevels(2026, 10);
    }

    @Test
    @DisplayName("getCollegeLevelsTrend should delegate year to service and wrap into ApiResponse")
    void getCollegeLevelsTrendShouldDelegateToService() {
        when(collegeLevelDashboardService.getCollegeLevelsTrend(2026)).thenReturn(
                CollegeLevelTrendResponseDTO.builder()
                        .year(2026)
                        .months(List.of())
                        .build()
        );

        ResponseEntity<?> response = assertDoesNotThrow(() -> controller.getCollegeLevelsTrend(2026));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.sism.common.ApiResponse<?>) response.getBody();
        assertEquals(200, body.getCode());
        assertEquals(2026, ((CollegeLevelTrendResponseDTO) body.getData()).getYear());
        verify(collegeLevelDashboardService).getCollegeLevelsTrend(2026);
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
