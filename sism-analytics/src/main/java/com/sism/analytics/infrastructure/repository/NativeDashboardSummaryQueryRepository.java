package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.DashboardSummaryQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class NativeDashboardSummaryQueryRepository implements DashboardSummaryQueryRepository {
    private static final int DASHBOARD_SUMMARY_COLUMN_COUNT = 8;
    private static final int DEPARTMENT_PROGRESS_COLUMN_COUNT = 5;
    private static final int RECENT_ACTIVITY_COLUMN_COUNT = 6;

    private static final String INDICATOR_VIEW = "analytics_indicator_dashboard_view";
    private static final String ALERT_VIEW = "analytics_unresolved_alert_dashboard_view";
    private static final String ACTIVE_ORG_VIEW = "analytics_active_org_dashboard_view";
    private static final String COLUMN_STATUS = "status";
    private static final String COLUMN_PROGRESS = "progress";
    private static final String COLUMN_TYPE = "type";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final String SQL_COUNT_ACTIVE_INDICATORS =
            "SELECT COUNT(*) FROM " + INDICATOR_VIEW;
    private static final String SQL_COUNT_COMPLETED_INDICATORS =
            "SELECT COUNT(*) FROM " + INDICATOR_VIEW + " WHERE " + COLUMN_STATUS + " = :completedStatus";
    private static final String SQL_AVG_ACTIVE_INDICATORS =
            "SELECT COALESCE(AVG(" + COLUMN_PROGRESS + "), 0) FROM " + INDICATOR_VIEW;
    private static final String SQL_AVG_ACTIVE_INDICATORS_BY_TYPE =
            "SELECT COALESCE(AVG(" + COLUMN_PROGRESS + "), 0) FROM " + INDICATOR_VIEW +
                    " WHERE " + COLUMN_TYPE + " = :type";
    private static final String SQL_COUNT_UNRESOLVED_ALERTS_BY_SEVERITY =
            "SELECT COUNT(*) FROM " + ALERT_VIEW + " WHERE severity = :severity";
    private static final String SQL_DASHBOARD_SUMMARY_METRICS = """
            SELECT
                i.total_indicators,
                i.completed_indicators,
                i.average_progress,
                i.basic_score,
                i.development_score,
                a.severe_alerts,
                a.moderate_alerts,
                a.normal_alerts
            FROM (
                SELECT
                    COUNT(*) AS total_indicators,
                    COUNT(CASE WHEN status = :completedStatus THEN 1 END) AS completed_indicators,
                    COALESCE(AVG(progress), 0) AS average_progress,
                    COALESCE(AVG(CASE WHEN type = :basicType THEN progress END), 0) AS basic_score,
                    COALESCE(AVG(CASE WHEN type = :developmentType THEN progress END), 0) AS development_score
                FROM analytics_indicator_dashboard_view
            ) i
            CROSS JOIN (
                SELECT
                    COALESCE(SUM(CASE WHEN severity = :criticalSeverity THEN 1 ELSE 0 END), 0) AS severe_alerts,
                    COALESCE(SUM(CASE WHEN severity = :warningSeverity THEN 1 ELSE 0 END), 0) AS moderate_alerts,
                    COALESCE(SUM(CASE WHEN severity = :infoSeverity THEN 1 ELSE 0 END), 0) AS normal_alerts
                FROM analytics_unresolved_alert_dashboard_view
            ) a
            """;
    /**
     * 视角收缩版汇总（2026-11-27 角色分流）：指标与预警都限定 target_org = 本组织。
     * 预警视图不带 target_org_id，需经 indicator_id 回连指标视图过滤。
     */
    private static final String SQL_DASHBOARD_SUMMARY_METRICS_SCOPED = """
            SELECT
                i.total_indicators,
                i.completed_indicators,
                i.average_progress,
                i.basic_score,
                i.development_score,
                a.severe_alerts,
                a.moderate_alerts,
                a.normal_alerts
            FROM (
                SELECT
                    COUNT(*) AS total_indicators,
                    COUNT(CASE WHEN status = :completedStatus THEN 1 END) AS completed_indicators,
                    COALESCE(AVG(progress), 0) AS average_progress,
                    COALESCE(AVG(CASE WHEN type = :basicType THEN progress END), 0) AS basic_score,
                    COALESCE(AVG(CASE WHEN type = :developmentType THEN progress END), 0) AS development_score
                FROM analytics_indicator_dashboard_view
                WHERE target_org_id = :scopeOrgId
            ) i
            CROSS JOIN (
                SELECT
                    COALESCE(SUM(CASE WHEN ae.severity = :criticalSeverity THEN 1 ELSE 0 END), 0) AS severe_alerts,
                    COALESCE(SUM(CASE WHEN ae.severity = :warningSeverity THEN 1 ELSE 0 END), 0) AS moderate_alerts,
                    COALESCE(SUM(CASE WHEN ae.severity = :infoSeverity THEN 1 ELSE 0 END), 0) AS normal_alerts
                FROM analytics_unresolved_alert_dashboard_view ae
                INNER JOIN analytics_indicator_dashboard_view i2
                        ON i2.id = ae.indicator_id
                       AND i2.target_org_id = :scopeOrgId
            ) a
            """;
    private static final String SQL_DEPARTMENT_PROGRESS = """
            SELECT
                o.name AS dept,
                COALESCE(AVG(i.progress), 0) AS avg_progress,
                COUNT(i.id) AS total_indicators,
                COUNT(CASE WHEN i.status = :completedStatus THEN 1 END) AS completed_indicators,
                COALESCE(MAX(a.alert_count), 0) AS alert_count
            FROM analytics_active_org_dashboard_view o
            LEFT JOIN analytics_indicator_dashboard_view i ON i.target_org_id = o.id
            LEFT JOIN (
                SELECT i2.target_org_id, COUNT(*) AS alert_count
                FROM analytics_unresolved_alert_dashboard_view ae
                INNER JOIN analytics_indicator_dashboard_view i2 ON i2.id = ae.indicator_id
                GROUP BY i2.target_org_id
            ) a ON a.target_org_id = o.id
            GROUP BY o.id, o.name
            ORDER BY o.name
            """;
    /** 视角收缩版部门进度（2026-11-27 角色分流）：只返回本组织一行 */
    private static final String SQL_DEPARTMENT_PROGRESS_SCOPED = """
            SELECT
                o.name AS dept,
                COALESCE(AVG(i.progress), 0) AS avg_progress,
                COUNT(i.id) AS total_indicators,
                COUNT(CASE WHEN i.status = :completedStatus THEN 1 END) AS completed_indicators,
                COALESCE(MAX(a.alert_count), 0) AS alert_count
            FROM analytics_active_org_dashboard_view o
            LEFT JOIN analytics_indicator_dashboard_view i ON i.target_org_id = o.id
            LEFT JOIN (
                SELECT i2.target_org_id, COUNT(*) AS alert_count
                FROM analytics_unresolved_alert_dashboard_view ae
                INNER JOIN analytics_indicator_dashboard_view i2 ON i2.id = ae.indicator_id
                GROUP BY i2.target_org_id
            ) a ON a.target_org_id = o.id
            WHERE o.id = :scopeOrgId
            GROUP BY o.id, o.name
            ORDER BY o.name
            """;
    private static final String SQL_RECENT_ACTIVITIES_TEMPLATE = """
            SELECT i.id, i.indicator_desc, i.status, i.progress, i.updated_at,
                   o.name AS org_name
            FROM analytics_indicator_dashboard_view i
            LEFT JOIN analytics_active_org_dashboard_view o ON o.id = i.target_org_id
            WHERE i.updated_at IS NOT NULL
            ORDER BY i.updated_at DESC
            """;
    /** 视角收缩版最近活动（2026-11-27 角色分流）：只统计指标 target=本组织的行 */
    private static final String SQL_RECENT_ACTIVITIES_SCOPED_TEMPLATE = """
            SELECT i.id, i.indicator_desc, i.status, i.progress, i.updated_at,
                   o.name AS org_name
            FROM analytics_indicator_dashboard_view i
            LEFT JOIN analytics_active_org_dashboard_view o ON o.id = i.target_org_id
            WHERE i.updated_at IS NOT NULL
              AND i.target_org_id = :scopeOrgId
            ORDER BY i.updated_at DESC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public long countActiveIndicators() {
        return toLong(entityManager.createNativeQuery(SQL_COUNT_ACTIVE_INDICATORS).getSingleResult());
    }

    @Override
    public long countCompletedIndicators() {
        return toLong(entityManager.createNativeQuery(SQL_COUNT_COMPLETED_INDICATORS)
                .setParameter("completedStatus", STATUS_COMPLETED)
                .getSingleResult());
    }

    @Override
    public double averageActiveProgress() {
        return toDouble(entityManager.createNativeQuery(SQL_AVG_ACTIVE_INDICATORS).getSingleResult());
    }

    @Override
    public double averageActiveProgressByType(String type) {
        return toDouble(entityManager.createNativeQuery(SQL_AVG_ACTIVE_INDICATORS_BY_TYPE)
                .setParameter("type", type)
                .getSingleResult());
    }

    @Override
    public long countUnresolvedAlertsBySeverity(String severity) {
        return toLong(entityManager.createNativeQuery(SQL_COUNT_UNRESOLVED_ALERTS_BY_SEVERITY)
                .setParameter("severity", severity)
                .getSingleResult());
    }

    @Override
    public DashboardSummaryMetrics fetchDashboardSummaryMetrics(Long targetOrgId) {
        boolean scoped = targetOrgId != null;
        var query = entityManager
                .createNativeQuery(scoped ? SQL_DASHBOARD_SUMMARY_METRICS_SCOPED : SQL_DASHBOARD_SUMMARY_METRICS)
                .setParameter("completedStatus", STATUS_COMPLETED)
                .setParameter("basicType", "基础性指标")
                .setParameter("developmentType", "发展性指标")
                .setParameter("criticalSeverity", "CRITICAL")
                .setParameter("warningSeverity", "WARNING")
                .setParameter("infoSeverity", "INFO");
        if (scoped) {
            query.setParameter("scopeOrgId", targetOrgId);
        }
        Object[] row = (Object[]) query.getSingleResult();
        requireColumnCount(row, DASHBOARD_SUMMARY_COLUMN_COUNT, "dashboard summary");
        return new DashboardSummaryMetrics(
                toLong(row[0]),
                toLong(row[1]),
                toDouble(row[2]),
                toDouble(row[3]),
                toDouble(row[4]),
                toLong(row[5]),
                toLong(row[6]),
                toLong(row[7])
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DepartmentProgressRow> fetchDepartmentProgressRows(Long targetOrgId) {
        var query = entityManager
                .createNativeQuery(targetOrgId == null ? SQL_DEPARTMENT_PROGRESS : SQL_DEPARTMENT_PROGRESS_SCOPED)
                .setParameter("completedStatus", STATUS_COMPLETED);
        if (targetOrgId != null) {
            query.setParameter("scopeOrgId", targetOrgId);
        }
        List<Object[]> rows = query.getResultList();
        List<DepartmentProgressRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            requireColumnCount(row, DEPARTMENT_PROGRESS_COLUMN_COUNT, "department progress");
            result.add(new DepartmentProgressRow(
                    (String) row[0],
                    toDouble(row[1]),
                    toLong(row[2]),
                    toLong(row[3]),
                    toLong(row[4])
            ));
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RecentActivityRow> fetchRecentActivityRows(int limit, Long targetOrgId) {
        int safeLimit = Math.max(limit, 1);
        var query = entityManager
                .createNativeQuery(targetOrgId == null
                        ? SQL_RECENT_ACTIVITIES_TEMPLATE
                        : SQL_RECENT_ACTIVITIES_SCOPED_TEMPLATE)
                .setMaxResults(safeLimit);
        if (targetOrgId != null) {
            query.setParameter("scopeOrgId", targetOrgId);
        }
        List<Object[]> rows = query.getResultList();
        List<RecentActivityRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            requireColumnCount(row, RECENT_ACTIVITY_COLUMN_COUNT, "recent activities");
            result.add(new RecentActivityRow(
                    toLong(row[0]),
                    row[1] == null ? null : row[1].toString(),
                    row[2] == null ? null : row[2].toString(),
                    toDouble(row[3]),
                    row[4] == null ? null : row[4].toString(),
                    row[5] == null ? null : row[5].toString()
            ));
        }
        return result;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private static void requireColumnCount(Object[] row, int expected, String queryName) {
        if (row == null || row.length != expected) {
            throw new IllegalStateException("Unexpected column count for " + queryName + ": expected "
                    + expected + " but was " + (row == null ? 0 : row.length));
        }
    }
}
