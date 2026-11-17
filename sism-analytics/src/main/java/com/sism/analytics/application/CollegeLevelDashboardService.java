package com.sism.analytics.application;

import com.sism.analytics.domain.repository.CollegeLevelQueryRepository;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeDispatchCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelMonthlyCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeReportedMonthRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.DelayedIndicatorRow;
import com.sism.analytics.interfaces.dto.CollegeLevelDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO.CollegeLevelMonthDTO;
import com.sism.analytics.interfaces.dto.DelayedIndicatorsResponseDTO;
import com.sism.analytics.interfaces.dto.DelayedIndicatorsResponseDTO.DelayedIndicatorDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * CollegeLevelDashboardService - 学院指标完成情况/月度趋势/滞后任务清单聚合服务。
 *
 * <p>2026-11-17 用户拍板口径（升级自 2026-10-31 版）：数据看板改由月报真实数据聚合——</p>
 * <ul>
 *   <li>只算已提交/已审批月报，DRAFT 不计入（含「未填报」判定：仅有 DRAFT 月报 = 未填报）</li>
 *   <li>等级优先级 appraisal_level &gt; self_rating</li>
 *   <li>未填报学院也要返回（前端画灰柱「下发 n 项·未填报」督办）</li>
 *   <li>每学院附带已下发/未下发指标数（按年）</li>
 *   <li>手动预警（alert_event）彻底退出看板</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class CollegeLevelDashboardService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2999;
    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;

    private final CollegeLevelQueryRepository collegeLevelQueryRepository;

    public CollegeLevelDashboardService(CollegeLevelQueryRepository collegeLevelQueryRepository) {
        this.collegeLevelQueryRepository = collegeLevelQueryRepository;
    }

    /**
     * 获取指定月份各学院鉴定等级计数。
     * 学院集合 = 当年有指标（已下发/未下发）的学院 ∪ 当月已上报学院；
     * 未填报学院（当月无已提交/已审批月报）同样返回，reported=false 供前端画灰柱督办。
     */
    public CollegeLevelsResponseDTO getCollegeLevels(Integer year, Integer month) {
        requireYear(year);
        requireMonth(month);
        String reportMonth = toReportMonth(year, month);

        Map<Long, CollegeDispatchCountRow> dispatchByOrgId = indexDispatchCounts(year);
        Map<Long, String> reportedOrgNames = collectReportedOrgNamesByMonth(year, month, month);
        Map<Long, CollegeLevelCountRow> levelsByOrgId = indexLevelCounts(reportMonth);

        Set<Long> orgIds = new TreeSet<>(dispatchByOrgId.keySet());
        orgIds.addAll(reportedOrgNames.keySet());

        List<CollegeLevelDTO> colleges = new ArrayList<>(orgIds.size());
        for (Long orgId : orgIds) {
            colleges.add(toCollegeLevel(
                    orgId, month, dispatchByOrgId, levelsByOrgId, reportedOrgNames));
        }
        return CollegeLevelsResponseDTO.builder()
                .year(year)
                .month(month)
                .colleges(colleges)
                .build();
    }

    /**
     * 获取年度趋势。固定返回 1-12 月全量月份（升序），
     * 每月学院集合 = 当年有指标的学院 ∪ 当月已上报学院；未填报学院 reported=false。
     */
    public CollegeLevelTrendResponseDTO getCollegeLevelsTrend(Integer year) {
        requireYear(year);
        Map<Long, CollegeDispatchCountRow> dispatchByOrgId = indexDispatchCounts(year);
        Map<Integer, Map<Long, String>> reportedOrgNamesByMonth = collectReportedOrgNamesByMonth(year);
        Map<Integer, Map<Long, CollegeLevelCountRow>> levelsByMonthAndOrg =
                indexMonthlyLevelCounts(year);

        List<CollegeLevelMonthDTO> months = new ArrayList<>(MAX_MONTH);
        for (int month = MIN_MONTH; month <= MAX_MONTH; month++) {
            Map<Long, String> reportedOrgNames =
                    reportedOrgNamesByMonth.getOrDefault(month, Map.of());
            Set<Long> orgIds = new TreeSet<>(dispatchByOrgId.keySet());
            orgIds.addAll(reportedOrgNames.keySet());

            Map<Long, CollegeLevelCountRow> levelsForMonth =
                    levelsByMonthAndOrg.getOrDefault(month, Map.of());
            List<CollegeLevelDTO> colleges = new ArrayList<>(orgIds.size());
            for (Long orgId : orgIds) {
                colleges.add(toCollegeLevel(
                        orgId, month, dispatchByOrgId, levelsForMonth, reportedOrgNames));
            }
            months.add(CollegeLevelMonthDTO.builder()
                    .month(month)
                    .colleges(colleges)
                    .build());
        }
        return CollegeLevelTrendResponseDTO.builder()
                .year(year)
                .months(months)
                .build();
    }

    /**
     * 获取指定月份鉴定为 DELAYED（appraisal_level 优先，self_rating 兜底）的滞后指标清单。
     * 同一指标多轮上报（如驳回重报遗留 IN_REVIEW）时按接口排序取首条去重，避免重复催办。
     */
    public DelayedIndicatorsResponseDTO getDelayedIndicators(Integer year, Integer month) {
        requireYear(year);
        requireMonth(month);
        String reportMonth = toReportMonth(year, month);

        Map<Long, DelayedIndicatorDTO> byIndicatorId = new LinkedHashMap<>();
        for (DelayedIndicatorRow row : collegeLevelQueryRepository.fetchDelayedIndicators(reportMonth)) {
            byIndicatorId.computeIfAbsent(row.indicatorId(), indicatorId -> toDelayedIndicator(row));
        }
        List<DelayedIndicatorDTO> items = List.copyOf(byIndicatorId.values());
        return DelayedIndicatorsResponseDTO.builder()
                .year(year)
                .month(month)
                .items(items)
                .build();
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private static CollegeLevelDTO toCollegeLevel(
            Long orgId,
            int month,
            Map<Long, CollegeDispatchCountRow> dispatchByOrgId,
            Map<Long, CollegeLevelCountRow> levelsByOrgId,
            Map<Long, String> reportedOrgNames
    ) {
        CollegeDispatchCountRow dispatch = dispatchByOrgId.get(orgId);
        CollegeLevelCountRow levels = levelsByOrgId.get(orgId);
        boolean reported = reportedOrgNames.containsKey(orgId);
        return CollegeLevelDTO.builder()
                .orgId(orgId)
                .orgName(resolveOrgName(orgId, dispatch, levels, reportedOrgNames.get(orgId)))
                .month(month)
                .ahead(levels == null ? 0L : levels.ahead())
                .normal(levels == null ? 0L : levels.normal())
                .delayed(levels == null ? 0L : levels.delayed())
                .total(levels == null ? 0L : levels.total())
                .dispatchedCount(dispatch == null ? 0L : dispatch.dispatchedCount())
                .undispatchedCount(dispatch == null ? 0L : dispatch.undispatchedCount())
                .reported(reported)
                .build();
    }

    /**
     * 学院名解析优先级：下发计数行 &gt; 等级计数行 &gt; 已上报行（reported 查询自带组织名）。
     */
    private static String resolveOrgName(
            Long orgId,
            CollegeDispatchCountRow dispatch,
            CollegeLevelCountRow levels,
            String reportedOrgName
    ) {
        if (dispatch != null && notBlank(dispatch.orgName())) {
            return dispatch.orgName();
        }
        if (levels != null && notBlank(levels.orgName())) {
            return levels.orgName();
        }
        if (notBlank(reportedOrgName)) {
            return reportedOrgName;
        }
        return "未知学院#" + orgId;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private Map<Long, CollegeDispatchCountRow> indexDispatchCounts(Integer year) {
        Map<Long, CollegeDispatchCountRow> byOrgId = new HashMap<>();
        for (CollegeDispatchCountRow row : collegeLevelQueryRepository.fetchCollegeDispatchCounts(year)) {
            byOrgId.put(row.orgId(), row);
        }
        return byOrgId;
    }

    private Map<Long, CollegeLevelCountRow> indexLevelCounts(String reportMonth) {
        Map<Long, CollegeLevelCountRow> byOrgId = new HashMap<>();
        for (CollegeLevelCountRow row : collegeLevelQueryRepository.fetchCollegeLevelCounts(reportMonth)) {
            byOrgId.put(row.orgId(), row);
        }
        return byOrgId;
    }

    /** 单月：orgId → 学院名（来自已上报月报行） */
    private Map<Long, String> collectReportedOrgNamesByMonth(Integer year, int startMonth, int endMonth) {
        Map<Long, String> byOrgId = new HashMap<>();
        for (CollegeReportedMonthRow row : collegeLevelQueryRepository.fetchCollegeReportedMonths(
                toReportMonth(year, startMonth), toReportMonth(year, endMonth))) {
            byOrgId.putIfAbsent(row.orgId(), row.orgName());
        }
        return byOrgId;
    }

    /** 全年：month → (orgId → 学院名) */
    private Map<Integer, Map<Long, String>> collectReportedOrgNamesByMonth(Integer year) {
        Map<Integer, Map<Long, String>> byMonth = new TreeMap<>();
        for (CollegeReportedMonthRow row : collegeLevelQueryRepository
                .fetchCollegeReportedMonths(
                        toReportMonth(year, MIN_MONTH), toReportMonth(year, MAX_MONTH))) {
            byMonth.computeIfAbsent(row.month(), ignored -> new HashMap<>())
                    .putIfAbsent(row.orgId(), row.orgName());
        }
        return byMonth;
    }

    private Map<Integer, Map<Long, CollegeLevelCountRow>> indexMonthlyLevelCounts(Integer year) {
        Map<Integer, Map<Long, CollegeLevelCountRow>> byMonthAndOrg = new TreeMap<>();
        for (CollegeLevelMonthlyCountRow row : collegeLevelQueryRepository
                .fetchCollegeLevelMonthlyCounts(
                        toReportMonth(year, MIN_MONTH), toReportMonth(year, MAX_MONTH))) {
            byMonthAndOrg
                    .computeIfAbsent(row.month(), ignored -> new HashMap<>())
                    .put(row.orgId(), new CollegeLevelCountRow(
                            row.orgId(), row.orgName(), row.ahead(), row.normal(),
                            row.delayed(), row.total()));
        }
        return byMonthAndOrg;
    }

    private static DelayedIndicatorDTO toDelayedIndicator(DelayedIndicatorRow row) {
        return DelayedIndicatorDTO.builder()
                .indicatorId(row.indicatorId())
                .indicatorName(row.indicatorName())
                .orgId(row.orgId())
                .orgName(row.orgName())
                .ownerOrgName(row.ownerOrgName())
                .selfRating(row.selfRating())
                .appraisalLevel(row.appraisalLevel())
                .build();
    }

    /**
     * 年份必填且限定 2000-2999；月份必填且限定 1-12。非法输入由
     * GlobalExceptionHandler 统一映射为 400 并透传本条说人话 message。
     */
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
            throw new IllegalArgumentException("缺少必填参数 month（月份），请传入 " + MIN_MONTH + "-" + MAX_MONTH + " 之间的整数");
        }
        if (month < MIN_MONTH || month > MAX_MONTH) {
            throw new IllegalArgumentException("参数 month（月份）必须在 " + MIN_MONTH + "-" + MAX_MONTH + " 之间，当前值：" + month);
        }
    }

    private static String toReportMonth(int year, int month) {
        return "%d%02d".formatted(year, month);
    }
}
