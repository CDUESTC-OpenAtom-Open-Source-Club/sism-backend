package com.sism.alert.application;

import com.sism.alert.domain.Alert;
import com.sism.alert.domain.enums.AlertSeverity;
import com.sism.alert.domain.enums.AlertStatus;
import com.sism.alert.domain.repository.AlertRepository;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.shared.domain.exception.AuthorizationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertAccessServiceTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private IndicatorAccessPort indicatorAccessPort;

    @InjectMocks
    private AlertAccessService alertAccessService;

    @Test
    void filterAlertsByPermissionShouldKeepAccessibleIndicatorsOnly() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");

        Alert allowedAlert = alert(1L, 100L, AlertStatus.IN_PROGRESS);
        Alert forbiddenAlert = alert(2L, 200L, AlertStatus.IN_PROGRESS);

        when(indicatorAccessPort.findAccessibleIndicatorIds(35L)).thenReturn(java.util.Set.of(100L));

        List<Alert> filtered = alertAccessService.filterAlertsByPermission(List.of(allowedAlert, forbiddenAlert), authentication);

        assertEquals(List.of(allowedAlert), filtered);
    }

    @Test
    void countAlertsForCurrentOrgShouldAggregateByStatus() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");

        when(indicatorAccessPort.findAccessibleIndicatorIds(35L)).thenReturn(java.util.Set.of(100L, 200L));
        when(alertRepository.countByIndicatorIdIn(anyCollection())).thenReturn(7L);
        when(alertRepository.countByIndicatorIdInAndStatus(anyCollection(), eq(AlertStatus.OPEN))).thenReturn(3L);
        when(alertRepository.countByIndicatorIdInAndStatus(anyCollection(), eq(AlertStatus.IN_PROGRESS))).thenReturn(2L);
        when(alertRepository.countByIndicatorIdInAndStatus(anyCollection(), eq(AlertStatus.RESOLVED))).thenReturn(2L);

        var counts = alertAccessService.countAlertsForCurrentOrg(authentication);

        assertEquals(7L, counts.get("total"));
        assertEquals(3L, counts.get("pending"));
        assertEquals(2L, counts.get("triggered"));
        assertEquals(2L, counts.get("resolved"));
    }

    @Test
    void validateAlertAccessShouldRejectForeignIndicator() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");
        Alert alert = alert(1L, 999L, AlertStatus.OPEN);

        when(indicatorAccessPort.findAccessibleIndicatorIds(35L)).thenReturn(java.util.Set.of(100L));

        assertThrows(AuthorizationException.class, () -> alertAccessService.validateAlertAccess(alert, authentication));
    }

    @Test
    void requireAccessibleAlertShouldNotLeakIdentifierInExceptionMessage() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");

        when(alertRepository.findById(123L)).thenReturn(java.util.Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> alertAccessService.requireAccessibleAlert(123L, authentication)
        );

        assertEquals("Alert not found", exception.getMessage());
    }

    @Test
    void getAccessibleUnresolvedAlertsForAdminShouldUseStatusInPageQuery() {
        Authentication authentication = authentication(11L, 35L, "ROLE_VICE_PRESIDENT");
        PageRequest pageRequest = PageRequest.of(0, 10);

        when(alertRepository.findByStatusIn(List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS), pageRequest))
                .thenReturn(new PageImpl<>(List.of(alert(1L, 100L, AlertStatus.OPEN)), pageRequest, 1));

        var page = alertAccessService.getAccessibleUnresolvedAlerts(authentication, pageRequest);

        assertEquals(1, page.getContent().size());
        verify(alertRepository).findByStatusIn(List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS), pageRequest);
    }

    @Test
    void filterAccessibleIndicatorIdsShouldKeepOnlyAccessibleOnes() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");

        when(indicatorAccessPort.findAccessibleIndicatorIds(35L)).thenReturn(java.util.Set.of(100L, 200L));

        // 混合场景：列表里既有有权指标也有无权指标，应只保留有权的，而不是整体拒绝
        List<Long> filtered = alertAccessService.filterAccessibleIndicatorIds(
                List.of(100L, 200L, 300L),
                authentication
        );

        assertEquals(List.of(100L, 200L), filtered);
    }

    @Test
    void filterAccessibleIndicatorIdsShouldReturnEmptyWhenNothingAccessible() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");

        when(indicatorAccessPort.findAccessibleIndicatorIds(35L)).thenReturn(java.util.Set.of());

        List<Long> filtered = alertAccessService.filterAccessibleIndicatorIds(List.of(100L, 200L), authentication);

        assertEquals(List.of(), filtered);
    }

    @Test
    void filterAccessibleIndicatorIdsShouldReturnAllForStrategicAdmin() {
        // 战略部负责人/分管校领导在预警模块被视为管理员，可见全部指标
        Authentication authentication = authentication(1L, 35L, "ROLE_STRATEGY_DEPT_HEAD");

        List<Long> filtered = alertAccessService.filterAccessibleIndicatorIds(
                List.of(100L, 200L, 200L),
                authentication
        );

        assertEquals(List.of(100L, 200L), filtered);
    }

    private Authentication authentication(Long userId, Long orgId, String authority) {
        CurrentUser currentUser = new CurrentUser(
                userId,
                String.valueOf(userId),
                "User-" + userId,
                "user@example.com",
                orgId,
                List.of(new SimpleGrantedAuthority(authority))
        );
        return UsernamePasswordAuthenticationToken.authenticated(
                currentUser,
                null,
                currentUser.getAuthorities()
        );
    }

    private Alert alert(Long id, Long indicatorId, AlertStatus status) {
        Alert alert = new Alert();
        alert.setId(id);
        alert.setIndicatorId(indicatorId);
        alert.setStatus(status);
        alert.setSeverity(AlertSeverity.WARNING);
        alert.setActualPercent(BigDecimal.valueOf(90));
        alert.setExpectedPercent(BigDecimal.valueOf(100));
        alert.setGapPercent(BigDecimal.valueOf(10));
        return alert;
    }
}
