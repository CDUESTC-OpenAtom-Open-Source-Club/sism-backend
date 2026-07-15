package com.sism.alert.infrastructure.persistence;

import com.sism.alert.domain.Alert;
import com.sism.alert.domain.enums.AlertSeverity;
import com.sism.alert.domain.enums.AlertStatus;
import com.sism.alert.domain.repository.AlertRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * JpaAlertRepository - Alert 的 JPA 仓储实现
 * 同时继承 JpaRepository 和领域层 AlertRepository 接口
 * 位于 infrastructure.persistence 包下，可被 @EnableJpaRepositories 扫描到
 */
@Repository
public interface JpaAlertRepository extends JpaRepository<Alert, Long>, AlertRepository {

    @Override
    Page<Alert> findAll(Pageable pageable);

    @Override
    List<Alert> findByStatus(AlertStatus status);

    @Override
    Page<Alert> findByStatus(AlertStatus status, Pageable pageable);

    @Override
    List<Alert> findByStatusIn(Collection<AlertStatus> statuses);

    @Override
    Page<Alert> findByStatusIn(Collection<AlertStatus> statuses, Pageable pageable);

    @Override
    List<Alert> findBySeverity(AlertSeverity severity);

    @Override
    Page<Alert> findBySeverity(AlertSeverity severity, Pageable pageable);

    @Override
    List<Alert> findByStatusAndSeverity(AlertStatus status, AlertSeverity severity);

    @Override
    Page<Alert> findByStatusAndSeverity(AlertStatus status, AlertSeverity severity, Pageable pageable);

    @Override
    List<Alert> findByIndicatorId(Long indicatorId);

    @Override
    Page<Alert> findByIndicatorId(Long indicatorId, Pageable pageable);

    @Override
    List<Alert> findByIndicatorIdIn(Collection<Long> indicatorIds);

    @Override
    Page<Alert> findByIndicatorIdIn(Collection<Long> indicatorIds, Pageable pageable);

    @Override
    List<Alert> findByIndicatorIdInAndStatus(Collection<Long> indicatorIds, AlertStatus status);

    @Override
    Page<Alert> findByIndicatorIdInAndStatus(Collection<Long> indicatorIds, AlertStatus status, Pageable pageable);

    @Override
    List<Alert> findByIndicatorIdInAndSeverity(Collection<Long> indicatorIds, AlertSeverity severity);

    @Override
    Page<Alert> findByIndicatorIdInAndSeverity(Collection<Long> indicatorIds, AlertSeverity severity, Pageable pageable);

    @Override
    List<Alert> findByIndicatorIdInAndStatusAndSeverity(
            Collection<Long> indicatorIds,
            AlertStatus status,
            AlertSeverity severity
    );

    @Override
    Page<Alert> findByIndicatorIdInAndStatusAndSeverity(
            Collection<Long> indicatorIds,
            AlertStatus status,
            AlertSeverity severity,
            Pageable pageable
    );

    @Override
    List<Alert> findByIndicatorIdInAndStatusIn(Collection<Long> indicatorIds, Collection<AlertStatus> statuses);

    @Override
    Page<Alert> findByIndicatorIdInAndStatusIn(Collection<Long> indicatorIds, Collection<AlertStatus> statuses, Pageable pageable);

    @Override
    List<Alert> findByIndicatorIdInAndStatusInOrderByUpdatedAtDesc(
            Collection<Long> indicatorIds,
            Collection<AlertStatus> statuses
    );

    @Override
    List<Alert> findBySeverityAndStatusNot(AlertSeverity severity, AlertStatus status);

    @Override
    long countByStatus(AlertStatus status);

    @Override
    long countBySeverity(AlertSeverity severity);

    @Override
    long countBySeverityAndStatus(AlertSeverity severity, AlertStatus status);

    @Override
    long countByIndicatorIdIn(Collection<Long> indicatorIds);

    @Override
    long countByIndicatorIdInAndStatus(Collection<Long> indicatorIds, AlertStatus status);

    @Override
    long countByIndicatorIdInAndSeverityAndStatus(Collection<Long> indicatorIds, AlertSeverity severity, AlertStatus status);

    @Query("""
            select a.severity as severity, count(a) as count
            from Alert a
            where a.status in :openStatuses
            group by a.severity
            """)
    List<AlertRepository.SeverityCount> countOpenBySeverity(@Param("openStatuses") Collection<AlertStatus> openStatuses);

    @Override
    default List<AlertRepository.SeverityCount> countOpenBySeverity() {
        return countOpenBySeverity(List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS));
    }

    @Query("""
            select a.severity as severity, count(a) as count
            from Alert a
            where a.indicatorId in :indicatorIds
              and a.status in :openStatuses
            group by a.severity
            """)
    List<AlertRepository.SeverityCount> countOpenBySeverityForIndicators(
            @Param("indicatorIds") Collection<Long> indicatorIds,
            @Param("openStatuses") Collection<AlertStatus> openStatuses
    );

    @Override
    default List<AlertRepository.SeverityCount> countOpenBySeverityForIndicators(Collection<Long> indicatorIds) {
        return countOpenBySeverityForIndicators(indicatorIds, List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS));
    }
}
