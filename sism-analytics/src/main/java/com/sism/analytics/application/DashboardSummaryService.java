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
     *
     * @param scope 数据视角（2026-11-27 角色分流，必须非 null：controller 侧由
     *              {@link DashboardScopeResolver} 解析；null 视为全量，
     *              缓存 key 表达式已做 null 兜底）
     */
    @Cacheable(cacheNames = "dashboard-summary", cacheManager = "analyticsCacheManager",
            key = "'summary:' + (#scope == null or #scope.isFull() ? 'FULL' : 'org' + #scope.orgId())")
    public DashboardSummaryDTO getDashboardSummary(DashboardScope scope) {
        Long scopeOrgId = scopeOrgIdOrNull(scope);
        DashboardSummaryQueryRepository.DashboardSummaryMetrics metrics =
                dashboardSummaryQueryRepository.fetchDashboardSummaryMetrics(scopeOrgId);

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
     *
     * <p>2026-11-27 角色分流：学院/职能部门视角只返回本组织一行（ departments 收敛为
     * 本学院/本部门），全量视角返回全部活跃组织。缓存 key 带视角维度，防止不同角色串数据。</p>
     */
    @Cacheable(cacheNames = "department-progress", cacheManager = "analyticsCacheManager",
            key = "'progress:' + (#scope == null or #scope.isFull() ? 'FULL' : 'org' + #scope.orgId())")
    public List<DepartmentProgressDTO> getDepartmentProgress(DashboardScope scope) {
        return dashboardSummaryQueryRepository.fetchDepartmentProgressRows(scopeOrgIdOrNull(scope)).stream()
                .map(this::toDepartmentProgress)
                .toList();
    }

    /**
     * Get recent activities - recent indicator changes
     */
    @Cacheable(cacheNames = "recent-activities", cacheManager = "analyticsCacheManager",
            key = "'recent:' + (#scope == null or #scope.isFull() ? 'FULL' : 'org' + #scope.orgId())")
    public List<Map<String, Object>> getRecentActivities(DashboardScope scope) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DashboardSummaryQueryRepository.RecentActivityRow row : dashboardSummaryQueryRepository
                .fetchRecentActivityRows(RECENT_ACTIVITY_LIMIT, scopeOrgIdOrNull(scope))) {
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

    /** 全量视角传 null（SQL 不带组织过滤）；学院/职能部门视角传本组织 ID */
    private static Long scopeOrgIdOrNull(DashboardScope scope) {
        DashboardScope effective = scope == null ? DashboardScope.full() : scope;
        return effective.isFull() ? null : effective.orgId();
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
