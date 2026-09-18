package com.sism.alert.application;

import com.sism.alert.domain.Alert;
import com.sism.alert.domain.enums.AlertSeverity;
import com.sism.alert.domain.enums.AlertStatus;
import com.sism.alert.domain.repository.AlertRepository;
import com.sism.alert.interfaces.dto.AlertStatsDTO;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertApplicationServiceTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AlertApplicationService alertApplicationService;

    @Test
    void createAlertShouldNormalizeSeverityAndPersistCanonicalStatus() {
        when(alertRepository.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Alert alert = alertApplicationService.createAlert(
                1L,
                2L,
                3L,
                "major",
                BigDecimal.valueOf(45.5),
                BigDecimal.valueOf(80.0),
                BigDecimal.valueOf(-34.5),
                "{}"
        );

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(captor.capture());

        assertSame(captor.getValue(), alert);
        assertEquals(AlertSeverity.WARNING, alert.getSeverity());
        assertEquals(AlertStatus.OPEN, alert.getStatus());
        verify(eventPublisher).publishAll(anyList());
    }

    @Test
    void getAlertsBySeverityShouldUseCanonicalSeverity() {
        Alert alert = new Alert();
        when(alertRepository.findBySeverity(AlertSeverity.WARNING)).thenReturn(List.of(alert));

        List<Alert> alerts = alertApplicationService.getAlertsBySeverity("major");

        assertEquals(List.of(alert), alerts);
        verify(alertRepository).findBySeverity(AlertSeverity.WARNING);
    }

    @Test
    void countBySeverityShouldCountCanonicalSeverityOnly() {
        when(alertRepository.countBySeverity(AlertSeverity.CRITICAL)).thenReturn(7L);

        long count = alertApplicationService.countBySeverity("critical");

        assertEquals(7L, count);
        verify(alertRepository).countBySeverity(AlertSeverity.CRITICAL);
    }

    @Test
    void triggerAndResolveShouldPublishDomainEvents() {
        Alert alert = new Alert();
        alert.setId(1L);
        alert.setIndicatorId(2L);
        alert.setRuleId(3L);
        alert.setWindowId(4L);
        alert.setSeverity(AlertSeverity.CRITICAL);
        alert.setActualPercent(BigDecimal.valueOf(90));
        alert.setExpectedPercent(BigDecimal.valueOf(100));
        alert.setGapPercent(BigDecimal.valueOf(10));
        alert.setStatus(AlertStatus.OPEN);

        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        alertApplicationService.triggerAlert(1L);
        alertApplicationService.resolveAlert(1L, 8L, "done");

        verify(eventPublisher, times(2)).publishAll(anyList());
    }

    @Test
    void missingAlertShouldNotLeakIdentifierInExceptionMessage() {
        when(alertRepository.findById(99L)).thenReturn(Optional.empty());

        IllegalArgumentException triggerEx = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> alertApplicationService.triggerAlert(99L)
        );
        IllegalArgumentException resolveEx = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> alertApplicationService.resolveAlert(99L, 8L, "done")
        );

        assertEquals("Alert not found", triggerEx.getMessage());
        assertEquals("Alert not found", resolveEx.getMessage());
    }

    @Test
    void getAlertStatsShouldExposeCanonicalSeverityKeys() {
        when(alertRepository.countOpenBySeverity()).thenReturn(List.of(
                severityCount(AlertSeverity.CRITICAL, 1L),
                severityCount(AlertSeverity.WARNING, 3L),
                severityCount(AlertSeverity.INFO, 5L)
        ));

        AlertStatsDTO stats = alertApplicationService.getAlertStats();

        assertEquals(9L, stats.getTotalOpen());
        Map<String, Long> countBySeverity = stats.getCountBySeverity();
        assertEquals(1L, countBySeverity.get("CRITICAL"));
        assertEquals(3L, countBySeverity.get("WARNING"));
        assertEquals(5L, countBySeverity.get("INFO"));
    }

    @Test
    void getAlertsByStatusShouldNormalizeLegacyAliases() {
        Alert alert = new Alert();
        when(alertRepository.findByStatus(AlertStatus.IN_PROGRESS)).thenReturn(List.of(alert));

        List<Alert> alerts = alertApplicationService.getAlertsByStatus("triggered");

        assertEquals(List.of(alert), alerts);
        verify(alertRepository).findByStatus(AlertStatus.IN_PROGRESS);
    }

    @Test
    void getUnresolvedAlertsShouldUseSingleStatusInQuery() {
        Alert open = new Alert();
        Alert progress = new Alert();
        when(alertRepository.findByStatusIn(List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS)))
                .thenReturn(List.of(open, progress));

        List<Alert> alerts = alertApplicationService.getUnresolvedAlerts();

        assertEquals(List.of(open, progress), alerts);
        verify(alertRepository).findByStatusIn(List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS));
    }

    @Test
    void setManualAlertLevelShouldAcceptThreeTierProgressLevels() {
        when(alertRepository.findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(anyList(), anyList()))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class)))
                .thenReturn(List.of(7201L))
                .thenReturn(List.of(7101L));
        when(alertRepository.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<Alert> saved = alertApplicationService.setManualAlertLevel(2039L, "DELAYED", 9001L);

        assertTrue(saved.isPresent());
        assertEquals(AlertSeverity.DELAYED, saved.get().getSeverity());
        assertEquals(7201L, saved.get().getRuleId());
        assertEquals(7101L, saved.get().getWindowId());
        assertEquals(AlertStatus.OPEN, saved.get().getStatus());
    }

    @Test
    void setManualAlertLevelShouldAcceptAheadAndNormal() {
        when(alertRepository.findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(anyList(), anyList()))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class)))
                .thenReturn(List.of(7201L))
                .thenReturn(List.of(7101L));
        when(alertRepository.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<Alert> ahead = alertApplicationService.setManualAlertLevel(2039L, "AHEAD", 9001L);
        Optional<Alert> normal = alertApplicationService.setManualAlertLevel(2039L, "normal", 9001L);

        assertEquals(AlertSeverity.AHEAD, ahead.orElseThrow().getSeverity());
        assertEquals(AlertSeverity.NORMAL, normal.orElseThrow().getSeverity());
    }

    @Test
    void setManualAlertLevelShouldResolveExistingManualAlertsForSameIndicator() {
        Alert legacy = manualAlert(2039L, AlertSeverity.INFO);
        when(alertRepository.findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(anyList(), anyList()))
                .thenReturn(List.of(legacy));
        when(alertRepository.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class)))
                .thenReturn(List.of(7201L))
                .thenReturn(List.of(7101L));

        alertApplicationService.setManualAlertLevel(2039L, "DELAYED", 9001L);

        // 旧的手动等级被关闭，随后写入新的三档等级
        assertEquals(AlertStatus.RESOLVED, legacy.getStatus());
        verify(alertRepository, times(2)).save(any(Alert.class));
    }

    @Test
    void setManualAlertLevelShouldReturnEmptyForUnsupportedSeverity() {
        when(alertRepository.findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(anyList(), anyList()))
                .thenReturn(List.of());

        Optional<Alert> saved = alertApplicationService.setManualAlertLevel(2039L, "not-a-level", 9001L);

        assertFalse(saved.isPresent());
        verify(alertRepository, times(0)).save(any(Alert.class));
    }

    @Test
    void setManualAlertLevelShouldRequireIndicatorId() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> alertApplicationService.setManualAlertLevel(0L, "DELAYED", 9001L));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> alertApplicationService.setManualAlertLevel(null, "DELAYED", 9001L));
    }

    @Test
    void setManualAlertLevelShouldTreatNoneAsClearingWithoutNewAlert() {
        Alert legacy = manualAlert(2039L, AlertSeverity.DELAYED);
        when(alertRepository.findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(anyList(), anyList()))
                .thenReturn(List.of(legacy));
        when(alertRepository.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<Alert> saved = alertApplicationService.setManualAlertLevel(2039L, "NONE", 9001L);

        assertFalse(saved.isPresent());
        assertEquals(AlertStatus.RESOLVED, legacy.getStatus());
    }

    /** 带手动标记 detailJson 的存量告警（isManualStrategicTaskAlert 过滤依赖它）。 */
    private Alert manualAlert(Long indicatorId, AlertSeverity severity) {
        Alert alert = new Alert();
        alert.setIndicatorId(indicatorId);
        alert.setRuleId(7201L);
        alert.setWindowId(7101L);
        alert.setSeverity(severity);
        alert.setActualPercent(BigDecimal.ZERO);
        alert.setExpectedPercent(BigDecimal.ZERO);
        alert.setGapPercent(BigDecimal.ZERO);
        alert.setDetailJson("{\"source\":\"STRATEGIC_TASK_MANUAL\",\"message\":\"战略任务管理手动预警\"}");
        alert.setStatus(AlertStatus.OPEN);
        return alert;
    }

    private AlertRepository.SeverityCount severityCount(AlertSeverity severity, long count) {
        return new AlertRepository.SeverityCount() {
            @Override
            public AlertSeverity getSeverity() {
                return severity;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}
