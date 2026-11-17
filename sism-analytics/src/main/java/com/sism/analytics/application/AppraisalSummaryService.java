package com.sism.analytics.application;

import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository;
import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository.AppraisalRow;
import com.sism.analytics.interfaces.dto.AnnualLevelSummaryDTO;
import com.sism.analytics.interfaces.dto.AppraisalSummaryResponseDTO;
import com.sism.analytics.interfaces.dto.DepartmentLevelDTO;
import com.sism.analytics.interfaces.dto.DepartmentLevelMonthDTO;
import com.sism.analytics.interfaces.dto.LaggingTaskDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AppraisalSummaryService - 数据看板「审核鉴定」口径聚合。
 *
 * <p>2026-11-17 用户反馈修复：职能部门指标填报审核完成后，数据看板的
 * 「指标完成情况分布 / TOP 滞后任务响应清单 / 任务完成简报」都要跟着审核结果走。
 * 原先前端用指标 workflow status 兜底，根本没有三档（超前/正常/滞后），所以永远是
 * 「延期 0」。本服务统一从 plan_report_indicator.appraisal_level 聚合，三处共用一份数据。</p>
 *
 * <p><b>截止口径</b>：每个指标取「不晚于选中月份的最近一次鉴定」。这样当月没上报的部门
 * 仍显示上一次已定的结论，不会因为某个月没提交就把整张图清零；某月之后才改的鉴定
 * 也不会倒灌到过去的月份。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class AppraisalSummaryService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2999;
    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;

    /** plan_report.report_org_type 的职能部门取值（历史库亦可能是 sys_org.type 的 functional） */
    private static final String ORG_TYPE_FUNCTIONAL = "FUNC_DEPT";
    private static final String ORG_TYPE_FUNCTIONAL_LEGACY = "functional";

    private static final String LEVEL_AHEAD = "AHEAD";
    private static final String LEVEL_NORMAL = "NORMAL";
    private static final String LEVEL_DELAYED = "DELAYED";

    private static final int DEFAULT_LAGGING_LIMIT = 5;
    private static final int MAX_LAGGING_LIMIT = 50;

    private final AppraisalSummaryQueryRepository appraisalSummaryQueryRepository;

    public AppraisalSummaryService(AppraisalSummaryQueryRepository appraisalSummaryQueryRepository) {
        this.appraisalSummaryQueryRepository = appraisalSummaryQueryRepository;
    }

    /**
     * 汇总指定年月的看板数据。
     *
     * @param year   年份（必填，2000-2999）
     * @param month  月份（必填，1-12）
     * @param limit  滞后任务条数上限（可选，默认 5，最大 50）
     */
    public AppraisalSummaryResponseDTO getAppraisalSummary(Integer year, Integer month, Integer limit) {
        requireYear(year);
        requireMonth(month);
        int laggingLimit = normalizeLimit(limit);

        String startMonth = toReportMonth(year, MIN_MONTH);
        // 拉整年明细：简报与滞后清单一律按「年度最近一次鉴定」统计（选中月份无上报也不会清零），
        // 职能部门分布仍按选中月份截止，latestMonth 供前端在默认月份无数据时跳到最近有数据的月份。
        String endMonth = toReportMonth(year, MAX_MONTH);
        List<AppraisalRow> rows = appraisalSummaryQueryRepository.fetchAppraisalRows(startMonth, endMonth);

        List<DepartmentLevelMonthDTO> departmentMonths = new ArrayList<>(month);
        for (int m = MIN_MONTH; m <= month; m++) {
            departmentMonths.add(DepartmentLevelMonthDTO.builder()
                    .month(m)
                    .departments(departmentLevels(rows, m))
                    .build());
        }

        Map<Long, AppraisalRow> latestByIndicator = latestByIndicator(rows);

        return AppraisalSummaryResponseDTO.builder()
                .year(year)
                .month(month)
                .latestMonth(latestMonthOf(rows))
                .departments(departmentMonths.get(month - MIN_MONTH).getDepartments())
                .departmentMonths(departmentMonths)
                .laggingTasks(laggingTasks(latestByIndicator, laggingLimit))
                .annual(annualSummary(latestByIndicator))
                .build();
    }

    /**
     * 截至第 {@code upToMonth} 月，各职能部门「最近一次鉴定」的三档计数。
     * 只统计上报组织类型为 functional 的月报；当月无任何已鉴定上报的部门不返回。
     */
    private static List<DepartmentLevelDTO> departmentLevels(List<AppraisalRow> rows, int upToMonth) {
        Map<Long, String> orgNames = new LinkedHashMap<>();
        Map<Long, Map<Long, String>> latestLevelByOrg = new HashMap<>();
        Map<Long, Map<Long, String>> latestMonthByOrg = new HashMap<>();

        for (AppraisalRow row : rows) {
            if (!isFunctionalOrgType(row.reportOrgType())) {
                continue;
            }
            if (monthOf(row.reportMonth()) > upToMonth) {
                continue;
            }
            orgNames.putIfAbsent(row.reportOrgId(), row.reportOrgName());
            Map<Long, String> levels = latestLevelByOrg.computeIfAbsent(row.reportOrgId(), k -> new HashMap<>());
            Map<Long, String> months = latestMonthByOrg.computeIfAbsent(row.reportOrgId(), k -> new HashMap<>());
            String previousMonth = months.get(row.indicatorId());
            if (previousMonth == null || row.reportMonth().compareTo(previousMonth) >= 0) {
                months.put(row.indicatorId(), row.reportMonth());
                levels.put(row.indicatorId(), row.appraisalLevel());
            }
        }

        List<DepartmentLevelDTO> result = new ArrayList<>(orgNames.size());
        for (Map.Entry<Long, String> org : orgNames.entrySet()) {
            Map<Long, String> levels = latestLevelByOrg.getOrDefault(org.getKey(), Map.of());
            long ahead = countLevel(levels, LEVEL_AHEAD);
            long normal = countLevel(levels, LEVEL_NORMAL);
            long delayed = countLevel(levels, LEVEL_DELAYED);
            long total = ahead + normal + delayed;
            if (total == 0) {
                continue;
            }
            result.add(DepartmentLevelDTO.builder()
                    .orgId(org.getKey())
                    .orgName(org.getValue())
                    .ahead(ahead)
                    .normal(normal)
                    .delayed(delayed)
                    .total(total)
                    .build());
        }
        result.sort(Comparator.comparing(DepartmentLevelDTO::getOrgName,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    /**
     * 每个指标截至选中月份的最近一次鉴定（不分组织口径，用于滞后清单与年度简报）。
     */
    private static Map<Long, AppraisalRow> latestByIndicator(List<AppraisalRow> rows) {
        Map<Long, AppraisalRow> latest = new HashMap<>();
        for (AppraisalRow row : rows) {
            AppraisalRow previous = latest.get(row.indicatorId());
            if (previous == null || row.reportMonth().compareTo(previous.reportMonth()) >= 0) {
                latest.put(row.indicatorId(), row);
            }
        }
        return latest;
    }

    /**
     * 最近一次鉴定为 DELAYED 的指标，按权重降序（无权重排最后）取前 N 条。
     */
    private static List<LaggingTaskDTO> laggingTasks(Map<Long, AppraisalRow> latestByIndicator, int limit) {
        return latestByIndicator.values().stream()
                .filter(row -> LEVEL_DELAYED.equals(row.appraisalLevel()))
                .sorted(Comparator
                        .comparing(AppraisalSummaryService::weightOf, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(row -> row.indicatorName() == null ? "" : row.indicatorName()))
                .limit(limit)
                .map(row -> LaggingTaskDTO.builder()
                        .indicatorId(row.indicatorId())
                        .indicatorName(row.indicatorName())
                        .orgId(row.targetOrgId())
                        .orgName(row.targetOrgName())
                        .reportMonth(row.reportMonth())
                        .build())
                .toList();
    }

    private static AnnualLevelSummaryDTO annualSummary(Map<Long, AppraisalRow> latestByIndicator) {
        long ahead = 0;
        long normal = 0;
        long delayed = 0;
        for (AppraisalRow row : latestByIndicator.values()) {
            if (LEVEL_AHEAD.equals(row.appraisalLevel())) {
                ahead++;
            } else if (LEVEL_NORMAL.equals(row.appraisalLevel())) {
                normal++;
            } else if (LEVEL_DELAYED.equals(row.appraisalLevel())) {
                delayed++;
            }
        }
        long total = ahead + normal + delayed;
        int completionRate = total > 0 ? (int) Math.round((ahead + normal) * 100.0 / total) : 0;
        return AnnualLevelSummaryDTO.builder()
                .ahead(ahead)
                .normal(normal)
                .delayed(delayed)
                .total(total)
                .completionRate(completionRate)
                .build();
    }

    private static boolean isFunctionalOrgType(String reportOrgType) {
        if (reportOrgType == null) {
            return false;
        }
        return ORG_TYPE_FUNCTIONAL.equalsIgnoreCase(reportOrgType)
                || ORG_TYPE_FUNCTIONAL_LEGACY.equalsIgnoreCase(reportOrgType);
    }

    private static long countLevel(Map<Long, String> levels, String level) {
        return levels.values().stream().filter(level::equals).count();
    }

    private static BigDecimal weightOf(AppraisalRow row) {
        return row.weightPercent();
    }

    private static int latestMonthOf(List<AppraisalRow> rows) {
        int latest = 0;
        for (AppraisalRow row : rows) {
            int month = monthOf(row.reportMonth());
            if (month > latest) {
                latest = month;
            }
        }
        return latest;
    }

    private static int monthOf(String reportMonth) {
        if (reportMonth == null || reportMonth.length() < 6) {
            return 0;
        }
        return Integer.parseInt(reportMonth.substring(4, 6));
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LAGGING_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("参数 limit（滞后任务条数）必须大于 0，当前值：" + limit);
        }
        return Math.min(limit, MAX_LAGGING_LIMIT);
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
