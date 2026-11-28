package com.sism.analytics.domain.repository;

import java.util.List;

public interface DashboardSummaryQueryRepository {

    long countActiveIndicators();

    long countCompletedIndicators();

    double averageActiveProgress();

    double averageActiveProgressByType(String type);

    long countUnresolvedAlertsBySeverity(String severity);

    /**
     * 汇总指标/预警指标（2026-11-27 角色分流）。
     *
     * @param targetOrgId 责任单位组织 ID；null = 全量（不做组织过滤）
     */
    DashboardSummaryMetrics fetchDashboardSummaryMetrics(Long targetOrgId);

    /**
     * 部门进度行（2026-11-27 角色分流）。
     *
     * @param targetOrgId 限定只返回该组织的行；null = 全量（全部活跃组织）
     */
    List<DepartmentProgressRow> fetchDepartmentProgressRows(Long targetOrgId);

    /**
     * 最近活动行（2026-11-27 角色分流）。
     *
     * @param targetOrgId 只返回指标 target=该组织的行；null = 全量
     */
    List<RecentActivityRow> fetchRecentActivityRows(int limit, Long targetOrgId);

    record DepartmentProgressRow(
            String departmentName,
            double averageProgress,
            long totalIndicators,
            long completedIndicators,
            long alertCount
    ) {
    }

    record RecentActivityRow(
            long id,
            String description,
            String status,
            double progress,
            String updatedAt,
            String orgName
    ) {
    }

    record DashboardSummaryMetrics(
            long totalIndicators,
            long completedIndicators,
            double averageProgress,
            double basicScore,
            double developmentScore,
            long severeAlerts,
            long moderateAlerts,
            long normalAlerts
    ) {
    }
}
