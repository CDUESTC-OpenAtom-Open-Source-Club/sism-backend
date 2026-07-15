package com.sism.alert.application;

import com.sism.alert.domain.Alert;
import com.sism.alert.domain.enums.AlertSeverity;
import com.sism.alert.domain.enums.AlertStatus;
import com.sism.alert.domain.repository.AlertRepository;
import com.sism.alert.interfaces.dto.AlertStatsDTO;
import com.sism.shared.domain.model.base.DomainEvent;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AlertApplicationService - 预警应用服务
 * 负责预警相关的业务操作
 */
@Service
@RequiredArgsConstructor
public class AlertApplicationService {

    private static final String MANUAL_ALERT_MARKER = "STRATEGIC_TASK_MANUAL";
    private static final String MANUAL_ALERT_DETAIL_JSON =
            "{\"source\":\"STRATEGIC_TASK_MANUAL\",\"message\":\"战略任务管理手动预警\"}";
    private static final String MANUAL_RULE_NAME = "战略任务管理手动预警";
    private static final String MANUAL_WINDOW_NAME = "战略任务管理手动预警窗口";
    private static final List<AlertStatus> OPEN_STATUSES = List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS);

    private final AlertRepository alertRepository;
    private final DomainEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    // ==================== Create ====================

    @Transactional
    public Alert createAlert(Long indicatorId, Long ruleId, Long windowId,
                            String severity, BigDecimal actualPercent,
                            BigDecimal expectedPercent, BigDecimal gapPercent,
                            String detailJson) {
        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);
        if (normalizedSeverity == null) {
            throw new IllegalArgumentException("Severity must be INFO, WARNING, or CRITICAL");
        }
        Alert alert = new Alert();
        alert.setIndicatorId(indicatorId);
        alert.setRuleId(ruleId);
        alert.setWindowId(windowId);
        alert.setSeverity(normalizedSeverity);
        alert.setActualPercent(actualPercent);
        alert.setExpectedPercent(expectedPercent);
        alert.setGapPercent(gapPercent);
        alert.setDetailJson(detailJson);
        alert.setStatus(AlertStatus.OPEN);
        alert.validate();
        Alert saved = alertRepository.save(alert);
        saved.recordCreated();
        publishAndClearEvents(saved);
        return saved;
    }

    @Transactional
    public Alert triggerAlert(Long alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found"));
        alert.trigger();
        Alert saved = alertRepository.save(alert);
        publishAndClearEvents(saved);
        return saved;
    }

    // ==================== Read ====================

    public Optional<Alert> getAlertById(Long id) {
        return alertRepository.findById(id);
    }

    public List<Alert> getAllAlerts() {
        return alertRepository.findAll();
    }

    public List<Alert> getAlertsByStatus(String status) {
        AlertStatus normalizedStatus = Alert.normalizeStatus(status);
        if (normalizedStatus == null) {
            return List.of();
        }
        return alertRepository.findByStatus(normalizedStatus);
    }

    public List<Alert> getAlertsBySeverity(String severity) {
        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);
        if (normalizedSeverity == null) {
            return List.of();
        }
        return alertRepository.findBySeverity(normalizedSeverity);
    }

    public List<Alert> getAlertsByIndicatorId(Long indicatorId) {
        return alertRepository.findByIndicatorId(indicatorId);
    }

    public List<Alert> getUnresolvedAlerts() {
        return alertRepository.findByStatusIn(List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS));
    }

    public Map<Long, String> getCurrentManualAlertLevels(List<Long> indicatorIds) {
        if (indicatorIds == null || indicatorIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> levels = new LinkedHashMap<>();
        alertRepository.findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(indicatorIds, OPEN_STATUSES).stream()
                .filter(this::isManualStrategicTaskAlert)
                .filter(alert -> alert.getIndicatorId() != null && alert.getSeverity() != null)
                .forEach(alert -> levels.putIfAbsent(alert.getIndicatorId(), alert.getSeverity().name()));
        return levels;
    }

    public Page<Alert> getAllAlerts(Pageable pageable) {
        return alertRepository.findAll(pageable);
    }

    public Page<Alert> getAlertsByStatus(String status, Pageable pageable) {
        AlertStatus normalizedStatus = Alert.normalizeStatus(status);
        if (normalizedStatus == null) {
            return Page.empty(pageable);
        }
        return alertRepository.findByStatus(normalizedStatus, pageable);
    }

    public Page<Alert> getAlertsBySeverity(String severity, Pageable pageable) {
        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);
        if (normalizedSeverity == null) {
            return Page.empty(pageable);
        }
        return alertRepository.findBySeverity(normalizedSeverity, pageable);
    }

    public Page<Alert> getAlertsByIndicatorId(Long indicatorId, Pageable pageable) {
        return alertRepository.findByIndicatorId(indicatorId, pageable);
    }

    public Page<Alert> getUnresolvedAlerts(Pageable pageable) {
        return alertRepository.findByStatusIn(List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS), pageable);
    }

    // ==================== Update ====================

    @Transactional
    public Alert resolveAlert(Long alertId, Long handledBy, String handledNote) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found"));
        alert.resolve(handledBy, handledNote);
        Alert saved = alertRepository.save(alert);
        publishAndClearEvents(saved);
        return saved;
    }

    @Transactional
    public Optional<Alert> setManualAlertLevel(Long indicatorId, String severity, Long handledBy) {
        if (indicatorId == null || indicatorId <= 0) {
            throw new IllegalArgumentException("Indicator ID is required");
        }

        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);
        List<Alert> currentManualAlerts = alertRepository
                .findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(List.of(indicatorId), OPEN_STATUSES).stream()
                .filter(this::isManualStrategicTaskAlert)
                .toList();

        for (Alert alert : currentManualAlerts) {
            alert.resolve(handledBy, "战略任务管理手动调整预警等级");
            Alert savedResolvedAlert = alertRepository.save(alert);
            publishAndClearEvents(savedResolvedAlert);
        }

        if (normalizedSeverity == null) {
            return Optional.empty();
        }

        Alert alert = new Alert();
        alert.setIndicatorId(indicatorId);
        alert.setRuleId(resolveManualAlertRuleId(normalizedSeverity));
        alert.setWindowId(resolveManualAlertWindowId());
        alert.setSeverity(normalizedSeverity);
        alert.setActualPercent(BigDecimal.ZERO);
        alert.setExpectedPercent(BigDecimal.ZERO);
        alert.setGapPercent(BigDecimal.ZERO);
        alert.setDetailJson(MANUAL_ALERT_DETAIL_JSON);
        alert.setStatus(AlertStatus.OPEN);
        alert.validate();

        Alert saved = alertRepository.save(alert);
        saved.recordCreated();
        publishAndClearEvents(saved);
        return Optional.of(saved);
    }

    @Transactional
    public void deleteAlert(Long alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found"));
        alertRepository.delete(alert);
    }

    // ==================== Statistics ====================

    public long countAlerts() {
        return alertRepository.count();
    }

    public long countByStatus(String status) {
        AlertStatus normalizedStatus = Alert.normalizeStatus(status);
        if (normalizedStatus == null) {
            return 0L;
        }
        return alertRepository.countByStatus(normalizedStatus);
    }

    public long countBySeverity(String severity) {
        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);
        if (normalizedSeverity == null) {
            return 0L;
        }
        return alertRepository.countBySeverity(normalizedSeverity);
    }

    /**
     * Get alert statistics: totalOpen + countBySeverity breakdown
     */
    public AlertStatsDTO getAlertStats() {
        Map<String, Long> countBySeverity = toSeverityCountMap(alertRepository.countOpenBySeverity());
        long totalOpen = countBySeverity.values().stream().mapToLong(Long::longValue).sum();
        return new AlertStatsDTO(totalOpen, countBySeverity);
    }

    private Map<String, Long> toSeverityCountMap(List<AlertRepository.SeverityCount> counts) {
        Map<String, Long> countBySeverity = new LinkedHashMap<>();
        countBySeverity.put(AlertSeverity.CRITICAL.name(), 0L);
        countBySeverity.put(AlertSeverity.WARNING.name(), 0L);
        countBySeverity.put(AlertSeverity.INFO.name(), 0L);
        for (AlertRepository.SeverityCount count : counts) {
            if (count.getSeverity() != null) {
                countBySeverity.put(count.getSeverity().name(), count.getCount());
            }
        }
        return countBySeverity;
    }

    private boolean isManualStrategicTaskAlert(Alert alert) {
        return alert != null
                && alert.getDetailJson() != null
                && alert.getDetailJson().contains(MANUAL_ALERT_MARKER);
    }

    private Long resolveManualAlertWindowId() {
        List<Long> existing = jdbcTemplate.query(
                """
                SELECT window_id
                FROM public.alert_window
                WHERE name = ?
                ORDER BY window_id
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong("window_id"),
                MANUAL_WINDOW_NAME
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        Long cycleId = resolveLatestCycleId();
        LocalDate cutoffDate = LocalDate.of(LocalDate.now().getYear(), 12, 31);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO public.alert_window (
                    created_at, updated_at, cutoff_date, is_default, name, cycle_id
                )
                VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, false, ?, ?)
                RETURNING window_id
                """,
                Long.class,
                cutoffDate,
                MANUAL_WINDOW_NAME,
                cycleId
        );
    }

    private Long resolveManualAlertRuleId(AlertSeverity severity) {
        List<Long> existing = jdbcTemplate.query(
                """
                SELECT rule_id
                FROM public.alert_rule
                WHERE name = ? AND severity = ?
                ORDER BY rule_id
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong("rule_id"),
                MANUAL_RULE_NAME,
                severity.name()
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        Long cycleId = resolveLatestCycleId();
        BigDecimal threshold = switch (severity) {
            case CRITICAL -> BigDecimal.valueOf(30);
            case WARNING -> BigDecimal.valueOf(20);
            case INFO -> BigDecimal.valueOf(10);
        };
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO public.alert_rule (
                    created_at, updated_at, gap_threshold, is_enabled, name, severity, cycle_id
                )
                VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, true, ?, ?, ?)
                RETURNING rule_id
                """,
                Long.class,
                threshold,
                MANUAL_RULE_NAME,
                severity.name(),
                cycleId
        );
    }

    private Long resolveLatestCycleId() {
        List<Long> ids = jdbcTemplate.query(
                """
                SELECT id
                FROM public.cycle
                ORDER BY year DESC, id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong("id")
        );
        if (ids.isEmpty()) {
            throw new IllegalStateException("No assessment cycle found for manual alert setup");
        }
        return ids.get(0);
    }

    private void publishAndClearEvents(Alert alert) {
        List<DomainEvent> events = alert.getDomainEvents();
        eventPublisher.publishAll(events);
        alert.clearEvents();
    }
}
