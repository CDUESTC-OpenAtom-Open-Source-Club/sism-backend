package com.sism.analytics.application;

import com.sism.analytics.domain.repository.DashboardSummaryQueryRepository;
import com.sism.analytics.interfaces.dto.DashboardSummaryDTO;
import com.sism.analytics.interfaces.dto.DepartmentProgressDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashboardSummaryService - 仪表盘汇总服务
 * Reads analytics-owned dashboard views through a query repository.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class DashboardSummaryService {

    private static final int RECENT_ACTIVITY_LIMIT = 20;
    private static final double ON_TRACK_THRESHOLD = 80.0;
    private static final double AT_RISK_THRESHOLD = 50.0;

    private final DashboardSummaryQueryRepository dashboardSummaryQueryRepository;
    private final CacheManager cacheManager;

    public DashboardSummaryService(
            DashboardSummaryQueryRepository dashboardSummaryQueryRepository,
            @Qualifier("analyticsCacheManager") CacheManager cacheManager
    ) {
        this.dashboardSummaryQueryRepository = dashboardSummaryQueryRepository;
        this.cacheManager = cacheManager;
    }

    /**
     * Get dashboard summary aggregating indicator stats
     */
    @Cacheable(cacheNames = "dashboard-summary", cacheManager = "analyticsCacheManager", key = "'summary'")
    public DashboardSummaryDTO getDashboardSummary() {
        DashboardSummaryQueryRepository.DashboardSummaryMetrics metrics =
                dashboardSummaryQueryRepository.fetchDashboardSummaryMetrics();

        long totalIndicators = metrics.totalIndicators();
        long completedIndicators = metrics.completedIndicators();
        double completionRate = totalIndicators > 0 ? metrics.averageProgress() : 0.0;
        double basicScore = metrics.basicScore();
        double developmentScore = metrics.developmentScore();
        double totalScore = totalIndicators > 0 ? (basicScore + developmentScore) / 2.0 : 0.0;
        long severeCount = metrics.severeAlerts();
        long moderateCount = metrics.moderateAlerts();
        long normalCount = metrics.normalAlerts();
        long warningCount = severeCount + moderateCount + normalCount;

        return DashboardSummaryDTO.builder()
                .totalScore(round2(totalScore))
                .basicScore(round2(basicScore))
                .developmentScore(round2(developmentScore))
                .completionRate(round2(completionRate))
                .warningCount(warningCount)
                .totalIndicators(totalIndicators)
                .completedIndicators(completedIndicators)
                .alertIndicators(DashboardSummaryDTO.AlertIndicators.builder()
                        .severe(severeCount)
                        .moderate(moderateCount)
                        .normal(normalCount)
                        .build())
                .build();
    }

    /**
     * Get department progress grouped by target_org_id
     */
    @Cacheable(cacheNames = "department-progress", cacheManager = "analyticsCacheManager", key = "'progress'")
    public List<DepartmentProgressDTO> getDepartmentProgress() {
        return dashboardSummaryQueryRepository.fetchDepartmentProgressRows().stream()
                .map(this::toDepartmentProgress)
                .toList();
    }

    /**
     * Get recent activities - recent indicator changes
     */
    @Cacheable(cacheNames = "recent-activities", cacheManager = "analyticsCacheManager", key = "'recent'")
    public List<Map<String, Object>> getRecentActivities() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DashboardSummaryQueryRepository.RecentActivityRow row : dashboardSummaryQueryRepository.fetchRecentActivityRows(RECENT_ACTIVITY_LIMIT)) {
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("id", row.id());
            activity.put("description", row.description());
            activity.put("status", row.status());
            activity.put("progress", row.progress());
            activity.put("updatedAt", row.updatedAt());
            activity.put("orgName", row.orgName());
            result.add(activity);
        }
        return result;
    }

    public void evictCachedSummaries() {
        evictCache("dashboard-summary");
        evictCache("department-progress");
        evictCache("recent-activities");
    }

    private DepartmentProgressDTO toDepartmentProgress(DashboardSummaryQueryRepository.DepartmentProgressRow row) {
        double progress = row.averageProgress();
        return DepartmentProgressDTO.builder()
                .dept(row.departmentName())
                .progress(round2(progress))
                .score(round2(progress))
                .status(resolveDepartmentStatus(progress))
                .totalIndicators(row.totalIndicators())
                .completedIndicators(row.completedIndicators())
                .alertCount(row.alertCount())
                .build();
    }

    private String resolveDepartmentStatus(double progress) {
        if (progress >= ON_TRACK_THRESHOLD) {
            return "on_track";
        }
        if (progress >= AT_RISK_THRESHOLD) {
            return "at_risk";
        }
        return "behind";
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private void evictCache(String cacheName) {
        var cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}
