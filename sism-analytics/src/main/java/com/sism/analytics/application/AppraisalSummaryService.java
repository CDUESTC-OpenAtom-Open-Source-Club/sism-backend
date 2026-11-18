package com.sism.analytics.application;

import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository;
import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository.AppraisalRow;
import com.sism.analytics.interfaces.dto.AnnualLevelSummaryDTO;
import com.sism.analytics.interfaces.dto.AppraisalSummaryResponseDTO;
import com.sism.analytics.interfaces.dto.DepartmentLevelDTO;
import com.sism.analytics.interfaces.dto.DepartmentLevelMonthDTO;
import com.sism.analytics.interfaces.dto.LaggingTaskDTO;
import com.sism.analytics.interfaces.dto.MonthState;
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
 * <p><b>2026-11-18 用户拍板（按月快照 + 未出结果置灰，废除沿用逻辑）</b>：
 * 按月筛选时，当前月对指标的整体评判是一份<b>当月快照</b>——</p>
 * <ul>
 *   <li>该月填报且审批通过（APPROVED）→ 显示该月鉴定值；</li>
 *   <li>该月没填报 → 置灰数据（无鉴定值，部门不出现在当月列表中）；</li>
 *   <li>该月填报了但审批未通过（SUBMITTED/IN_REVIEW）→ 置灰数据（无鉴定值，
 *       部门带 {@code monthState=IN_REVIEW} 供前端标「审批中」）。</li>
 * </ul>
 *
 * <p><b>各月互不沿用</b>：旧口径「每指标取不晚于选中月的最近一次鉴定、当月未报沿用上次
 * 结论」导致未填报月份与最近月长得一模一样（用户反馈「10 月与 11 月所有月份一样」），
 * 已废除。鉴定值只从 {@code report_month = 选中月 且 status = APPROVED} 的行聚合。
 * 「latestMonth 自动跳月」逻辑保留：首屏定位本年度最近一个有鉴定数据（APPROVED 且等级
 * 非空）的月份。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class AppraisalSummaryService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2999;
    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;

    /**
     * 鉴定值唯一认可的月报状态（2026-11-18 按月快照口径）。
     * SUBMITTED / IN_REVIEW（legacy 别名）统一视为「审批中」，不出鉴定值。
     */
    private static final String REPORT_STATUS_APPROVED = "APPROVED";

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
     * 汇总指定年月的看板数据（严格当月快照口径）。
     *
     * <p>仍按整年拉取明细：{@code departmentMonths}（1 月至选中月的逐月快照）与
     * {@code latestMonth} 自动跳月都需要全年视野；但每个月的鉴定值只来自该月自己的
     * APPROVED 月报，互不沿用。</p>
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
        String endMonth = toReportMonth(year, MAX_MONTH);
        List<AppraisalRow> rows = appraisalSummaryQueryRepository.fetchAppraisalRows(startMonth, endMonth);

        // 逐月独立快照：第 m 月的部门分布只由第 m 月自己的月报决定（2026-11-18 拍板，废除沿用）
        List<DepartmentLevelMonthDTO> departmentMonths = new ArrayList<>(month);
        for (int m = MIN_MONTH; m <= month; m++) {
            departmentMonths.add(DepartmentLevelMonthDTO.builder()
                    .month(m)
                    .departments(departmentLevels(rows, m))
                    .build());
        }

        // 滞后清单与简报同样只认选中月的 APPROVED 鉴定（按月快照，不跨月沿用）
        List<AppraisalRow> selectedMonthApprovedRows = approvedRowsOfMonth(rows, month);

        return AppraisalSummaryResponseDTO.builder()
                .year(year)
                .month(month)
                .latestMonth(latestAppraisedMonthOf(rows))
                .departments(departmentMonths.get(month - MIN_MONTH).getDepartments())
                .departmentMonths(departmentMonths)
                .laggingTasks(laggingTasks(selectedMonthApprovedRows, laggingLimit))
                .annual(annualSummary(selectedMonthApprovedRows))
                .build();
    }

    /**
     * 指定月份的 APPROVED 明细行（按月快照的唯一鉴定值来源）。
     */
    private static List<AppraisalRow> approvedRowsOfMonth(List<AppraisalRow> rows, int month) {
        List<AppraisalRow> result = new ArrayList<>();
        for (AppraisalRow row : rows) {
            if (monthOf(row.reportMonth()) == month && isApproved(row.reportStatus())) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 选中月当月快照下的各职能部门三档计数（2026-11-18 拍板）。
     *
     * <ul>
     *   <li>只统计上报组织类型为 functional 的月报，且只统计该月自己的行（report_month == month）；</li>
     *   <li>鉴定值只来自 APPROVED 月报；</li>
     *   <li>该月已填报但审批中（SUBMITTED/IN_REVIEW）的部门：鉴定值全 0，
     *       {@code monthState=IN_REVIEW}，仍返回（前端置灰标「审批中」）；</li>
     *   <li>该月未填报的部门不返回（无鉴定值，前端按缺省置灰）；</li>
     *   <li>APPROVED 但当月没有任何已鉴定指标的部门仍不返回（无内容可画）。</li>
     * </ul>
     */
    private static List<DepartmentLevelDTO> departmentLevels(List<AppraisalRow> rows, int month) {
        Map<Long, String> orgNames = new LinkedHashMap<>();
        Map<Long, Boolean> approvedByOrg = new HashMap<>();
        Map<Long, Map<Long, String>> levelByOrgAndIndicator = new HashMap<>();

        for (AppraisalRow row : rows) {
            if (!isFunctionalOrgType(row.reportOrgType())) {
                continue;
            }
            if (monthOf(row.reportMonth()) != month) {
                continue;
            }
            orgNames.putIfAbsent(row.reportOrgId(), row.reportOrgName());
            if (!isApproved(row.reportStatus())) {
                continue;
            }
            approvedByOrg.put(row.reportOrgId(), Boolean.TRUE);
            Map<Long, String> levels =
                    levelByOrgAndIndicator.computeIfAbsent(row.reportOrgId(), k -> new HashMap<>());
            // 同一指标同月多轮上报时保留行序靠后的一条（ORDER BY 保证行序即时间序）
            levels.put(row.indicatorId(), row.appraisalLevel());
        }

        List<DepartmentLevelDTO> result = new ArrayList<>(orgNames.size());
        for (Map.Entry<Long, String> org : orgNames.entrySet()) {
            boolean approved = approvedByOrg.getOrDefault(org.getKey(), Boolean.FALSE);
            MonthState monthState = approved ? MonthState.APPROVED : MonthState.IN_REVIEW;
            Map<Long, String> levels = approved
                    ? levelByOrgAndIndicator.getOrDefault(org.getKey(), Map.of())
                    : Map.of();
            long ahead = countLevel(levels, LEVEL_AHEAD);
            long normal = countLevel(levels, LEVEL_NORMAL);
            long delayed = countLevel(levels, LEVEL_DELAYED);
            long total = ahead + normal + delayed;
            // 审批中部门即使无鉴定值也要返回（供前端置灰标「审批中」）；
            // APPROVED 但无任何已鉴定指标的部门不返回
            if (total == 0 && monthState != MonthState.IN_REVIEW) {
                continue;
            }
            result.add(DepartmentLevelDTO.builder()
                    .orgId(org.getKey())
                    .orgName(org.getValue())
                    .ahead(ahead)
                    .normal(normal)
                    .delayed(delayed)
                    .total(total)
                    .reported(true)
                    .monthState(monthState)
                    .build());
        }
        result.sort(Comparator.comparing(DepartmentLevelDTO::getOrgName,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    /**
     * 选中月 APPROVED 鉴定为 DELAYED 的指标，按权重降序（无权重排最后）取前 N 条。
     *
     * <p>2026-11-18 拍板：按月快照口径——不再取「年度最近一次鉴定」；
     * 职能部门线（上报组织为职能部门）与学院线（上报组织为学院）的滞后都会进清单
     * （行按指标 target 组织作为责任主体返回）。同一指标当月多轮上报按行序取最后一条。</p>
     */
    private static List<LaggingTaskDTO> laggingTasks(List<AppraisalRow> approvedRowsOfMonth, int limit) {
        Map<Long, AppraisalRow> latestByIndicator = new LinkedHashMap<>();
        for (AppraisalRow row : approvedRowsOfMonth) {
            latestByIndicator.put(row.indicatorId(), row);
        }
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

    /**
     * 简报口径（2026-11-18 拍板：随选中月快照走）——只统计选中月 APPROVED 鉴定，
     * 同一指标当月多轮上报按行序取最后一条；completionRate =
     * (ahead + normal) / total * 100，四舍五入。
     */
    private static AnnualLevelSummaryDTO annualSummary(List<AppraisalRow> approvedRowsOfMonth) {
        Map<Long, String> levelByIndicator = new LinkedHashMap<>();
        for (AppraisalRow row : approvedRowsOfMonth) {
            levelByIndicator.put(row.indicatorId(), row.appraisalLevel());
        }
        long ahead = 0;
        long normal = 0;
        long delayed = 0;
        for (String level : levelByIndicator.values()) {
            if (LEVEL_AHEAD.equals(level)) {
                ahead++;
            } else if (LEVEL_NORMAL.equals(level)) {
                normal++;
            } else if (LEVEL_DELAYED.equals(level)) {
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

    /**
     * 本年度最近一个「有鉴定数据」的月份（latestMonth 自动跳月用）。
     * 只有 APPROVED 且等级非空的行才算「有鉴定数据」——审批中/未填报月份无鉴定值，
     * 不作为首屏跳月目标（2026-11-18 按月快照口径）。
     */
    private static int latestAppraisedMonthOf(List<AppraisalRow> rows) {
        int latest = 0;
        for (AppraisalRow row : rows) {
            if (!isApproved(row.reportStatus()) || row.appraisalLevel() == null) {
                continue;
            }
            int month = monthOf(row.reportMonth());
            if (month > latest) {
                latest = month;
            }
        }
        return latest;
    }

    /** plan_report.status 是否为 APPROVED（IN_REVIEW 是 SUBMITTED 的 legacy 别名，均视为审批中） */
    private static boolean isApproved(String reportStatus) {
        return REPORT_STATUS_APPROVED.equals(reportStatus);
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
