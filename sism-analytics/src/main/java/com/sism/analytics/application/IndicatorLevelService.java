package com.sism.analytics.application;

import com.sism.analytics.domain.repository.IndicatorLevelQueryRepository;
import com.sism.analytics.domain.repository.IndicatorLevelQueryRepository.IndicatorLevelRow;
import com.sism.analytics.interfaces.dto.IndicatorLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.IndicatorLevelsResponseDTO.IndicatorLevelItemDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IndicatorLevelService - 行级鉴定明细（看板下钻明细面板数据源，2026-11-27 新增）。
 *
 * <p>口径与看板聚合一致：只有 APPROVED 月报计入（按月快照），鉴定优先自评
 * （level = COALESCE(appraisal_level, self_rating)）。行主体 = 指标 target 组织
 * （责任单位口径），因此「职能部门下钻」拿到的是部门自身行，不含归口到该部门的
 * 学院线行（与明细面板口径一致，2026-11-27 #14 拍板）。</p>
 *
 * <p>视角校验：学院/职能部门用户只能查自己组织的行，越权查询他人组织返回 403；
 * 全量视角（战略部/admin）可查任意组织，orgId 不传 = 全校。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class IndicatorLevelService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2999;
    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;

    private final IndicatorLevelQueryRepository indicatorLevelQueryRepository;

    public IndicatorLevelService(IndicatorLevelQueryRepository indicatorLevelQueryRepository) {
        this.indicatorLevelQueryRepository = indicatorLevelQueryRepository;
    }

    /**
     * 查询行级鉴定明细。
     *
     * @param year           年份（必填）
     * @param month          月份（可选；null = 年度模式，返回全年各月）
     * @param requestedOrgId 责任单位组织 ID（可选；学院/职能视角强制为本组织）
     * @param scope          当前用户的数据视角
     */
    public IndicatorLevelsResponseDTO getIndicatorLevels(
            Integer year, Integer month, Long requestedOrgId, DashboardScope scope) {
        requireYear(year);
        requireMonth(month);
        DashboardScope effectiveScope = scope == null ? DashboardScope.full() : scope;

        Long orgId = resolveOrgId(effectiveScope, requestedOrgId);
        String startMonth = toReportMonth(year, MIN_MONTH);
        String endMonth = toReportMonth(year, MAX_MONTH);

        // 同一指标同月多轮上报取行序靠后的一条（SQL ORDER BY 保证行序即时间序）
        Map<Long, IndicatorLevelItemDTO> latestByIndicator = new LinkedHashMap<>();
        for (IndicatorLevelRow row : indicatorLevelQueryRepository.fetchIndicatorLevels(
                startMonth, endMonth, orgId)) {
            int rowMonth = monthOf(row.reportMonth());
            if (month != null && rowMonth != month) {
                continue;
            }
            latestByIndicator.put(row.indicatorId(), toItem(row, rowMonth));
        }

        return IndicatorLevelsResponseDTO.builder()
                .year(year)
                .month(month)
                .orgId(orgId)
                .items(List.copyOf(latestByIndicator.values()))
                .build();
    }

    /**
     * 视角 → 查询组织 ID：全量视角可用任意组织（不传 = 全校）；
     * 学院/职能视角强制为本组织，越权查他人组织 → 403。
     */
    private static Long resolveOrgId(DashboardScope scope, Long requestedOrgId) {
        if (scope.isFull()) {
            return requestedOrgId;
        }
        if (requestedOrgId != null && !scope.orgId().equals(requestedOrgId)) {
            throw new AccessDeniedException(
                    "无权查看组织 " + requestedOrgId + " 的鉴定明细，只能查询自己组织的数据");
        }
        return scope.orgId();
    }

    private static IndicatorLevelItemDTO toItem(IndicatorLevelRow row, int month) {
        String level = row.appraisalLevel() != null ? row.appraisalLevel() : row.selfRating();
        return IndicatorLevelItemDTO.builder()
                .indicatorId(row.indicatorId())
                .indicatorName(row.indicatorName())
                .month(month)
                .selfRating(row.selfRating())
                .appraisalLevel(row.appraisalLevel())
                .level(level)
                .build();
    }

    private static int monthOf(String reportMonth) {
        if (reportMonth == null || reportMonth.length() < 6) {
            return 0;
        }
        return Integer.parseInt(reportMonth.substring(4, 6));
    }

    private static void requireYear(Integer year) {
        if (year == null) {
            throw new IllegalArgumentException("缺少必填参数 year（年份），请传入 " + MIN_YEAR + "-" + MAX_YEAR + " 之间的整数");
        }
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("参数 year（年份）必须在 " + MIN_YEAR + "-" + MAX_YEAR + " 之间，当前值：" + year);
        }
    }

    private static void requireMonth(Integer month) {
        if (month == null) {
            return;
        }
        if (month < MIN_MONTH || month > MAX_MONTH) {
            throw new IllegalArgumentException("参数 month（月份）必须在 " + MIN_MONTH + "-" + MAX_MONTH + " 之间，当前值：" + month);
        }
    }

    private static String toReportMonth(int year, int month) {
        return "%d%02d".formatted(year, month);
    }
}
