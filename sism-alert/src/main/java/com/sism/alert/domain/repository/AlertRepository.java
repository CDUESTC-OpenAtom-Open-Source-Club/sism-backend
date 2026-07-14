package com.sism.alert.domain.repository;

import com.sism.alert.domain.Alert;
import com.sism.alert.domain.enums.AlertSeverity;
import com.sism.alert.domain.enums.AlertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * AlertRepository - 预警仓储接口（领域层）
 * 定义领域层所需的仓储方法
 * 实际 JPA 实现位于 infrastructure.persistence.JpaAlertRepository
 */
public interface AlertRepository {

    Optional<Alert> findById(Long id);

    List<Alert> findAll();

    Page<Alert> findAll(Pageable pageable);

    List<Alert> findByIndicatorIdIn(Collection<Long> indicatorIds);

    Page<Alert> findByIndicatorIdIn(Collection<Long> indicatorIds, Pageable pageable);

    Alert save(Alert alert);

    void delete(Alert alert);

    long count();

    List<Alert> findByStatus(AlertStatus status);

    Page<Alert> findByStatus(AlertStatus status, Pageable pageable);

    List<Alert> findByStatusIn(Collection<AlertStatus> statuses);

    Page<Alert> findByStatusIn(Collection<AlertStatus> statuses, Pageable pageable);

    List<Alert> findBySeverity(AlertSeverity severity);

    Page<Alert> findBySeverity(AlertSeverity severity, Pageable pageable);

    List<Alert> findByStatusAndSeverity(AlertStatus status, AlertSeverity severity);

    Page<Alert> findByStatusAndSeverity(AlertStatus status, AlertSeverity severity, Pageable pageable);

    List<Alert> findByIndicatorId(Long indicatorId);

    Page<Alert> findByIndicatorId(Long indicatorId, Pageable pageable);

    List<Alert> findByIndicatorIdInAndStatus(Collection<Long> indicatorIds, AlertStatus status);

    Page<Alert> findByIndicatorIdInAndStatus(Collection<Long> indicatorIds, AlertStatus status, Pageable pageable);

    List<Alert> findByIndicatorIdInAndSeverity(Collection<Long> indicatorIds, AlertSeverity severity);

    Page<Alert> findByIndicatorIdInAndSeverity(Collection<Long> indicatorIds, AlertSeverity severity, Pageable pageable);

    List<Alert> findByIndicatorIdInAndStatusAndSeverity(
            Collection<Long> indicatorIds,
            AlertStatus status,
            AlertSeverity severity
    );

    Page<Alert> findByIndicatorIdInAndStatusAndSeverity(
            Collection<Long> indicatorIds,
            AlertStatus status,
            AlertSeverity severity,
            Pageable pageable
    );

    List<Alert> findByIndicatorIdInAndStatusIn(Collection<Long> indicatorIds, Collection<AlertStatus> statuses);

    Page<Alert> findByIndicatorIdInAndStatusIn(Collection<Long> indicatorIds, Collection<AlertStatus> statuses, Pageable pageable);

    List<Alert> findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(
            Collection<Long> indicatorIds,
            Collection<AlertStatus> statuses
    );

    List<Alert> findBySeverityAndStatusNot(AlertSeverity severity, AlertStatus status);

    default List<Alert> findUnresolvedBySeverity(String severity) {
        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);
        if (normalizedSeverity == null) {
            return List.of();
        }
        return findBySeverityAndStatusNot(normalizedSeverity, AlertStatus.RESOLVED);
    }

    long countByStatus(AlertStatus status);

    long countBySeverity(AlertSeverity severity);

    long countBySeverityAndStatus(AlertSeverity severity, AlertStatus status);

    long countByIndicatorIdIn(Collection<Long> indicatorIds);

    long countByIndicatorIdInAndStatus(Collection<Long> indicatorIds, AlertStatus status);

    long countByIndicatorIdInAndSeverityAndStatus(Collection<Long> indicatorIds, AlertSeverity severity, AlertStatus status);

    List<SeverityCount> countOpenBySeverity();

    List<SeverityCount> countOpenBySeverityForIndicators(Collection<Long> indicatorIds);

    interface SeverityCount {
        AlertSeverity getSeverity();
        long getCount();
    }
}
