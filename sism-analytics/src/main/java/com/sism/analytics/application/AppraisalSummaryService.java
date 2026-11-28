package com.sism.analytics.application;

import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository;
import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository.AppraisalRow;
import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository.DispatchCountRow;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        return getAppraisalSummary(year, month, limit, DashboardScope.full());
    }

    /**
     * 汇总指定年月的看板数据（严格当月快照口径），按 {@link DashboardScope} 分流视角。
     *
     * <p>2026-11-27 角色分流：学院视角只统计本院（行按指标 target=本院过滤，
     * departments 只含本院归口部门）；职能部门视角只统计本部门（简报与滞后清单取
     * 本部门职能线上报行 + 归口到本部门的学院线行；departments 只有本部门自身上报行，
     * 与全校视图里本部门那一行数字一致）；全量视角（战略部/admin）返回结构与原先完全一致。</p>
     *
     * <p>仍按整年拉取明细：{@code departmentMonths}（1 月至选中月的逐月快照）与
     * {@code latestMonth} 自动跳月都需要全年视野；但每个月的鉴定值只来自该月自己的
     * APPROVED 月报，互不沿用。</p>
     *
     * @param year   年份（必填，2000-2999）
     * @param month  月份（必填，1-12）
     * @param limit  滞后任务条数上限（可选，默认 5，最大 50）
     * @param scope  数据视角（null 视为全量）
     */
    public AppraisalSummaryResponseDTO getAppraisalSummary(
            Integer year, Integer month, Integer limit, DashboardScope scope) {
        requireYear(year);
        requireMonth(month);
        DashboardScope effectiveScope = scope == null ? DashboardScope.full() : scope;
        int laggingLimit = normalizeLimit(limit);

        String startMonth = toReportMonth(year, MIN_MONTH);
        String endMonth = toReportMonth(year, MAX_MONTH);
        List<AppraisalRow> rows = filterRowsByScope(
                effectiveScope, appraisalSummaryQueryRepository.fetchAppraisalRows(startMonth, endMonth));
        // 灰柱数据源（2026-11-18 验收补齐 + 2026-11-25 B2 灰柱底座）：当年已下发指标数
        // （含部门名），口径对齐 CollegeLevelDTO.dispatchedCount；纯下发部门据此长出灰柱行
        Map<Long, DispatchCountRow> dispatchByOrgId = indexDispatchCounts(year, effectiveScope);

        // 逐月独立快照：第 m 月的部门分布只由第 m 月自己的月报决定（2026-11-18 拍板，废除沿用）
        List<DepartmentLevelMonthDTO> departmentMonths = new ArrayList<>(month);
        for (int m = MIN_MONTH; m <= month; m++) {
            departmentMonths.add(DepartmentLevelMonthDTO.builder()
                    .month(m)
                    .departments(departmentLevels(rows, m, dispatchByOrgId, effectiveScope))
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
     * 按视角过滤上报明细行（2026-11-27 角色分流）。
     *
     * <ul>
     *   <li>学院视角：行按指标 target=本院过滤（学院月报 target_org = report_org，
     *       即本院自报的行）；</li>
     *   <li>职能部门视角：本部门职能线上报行（report_org=本部门）∪ 归口到本部门的
     *       学院线行（functional_org=本部门）——简报、滞后清单、latestMonth 用这份行集；
     *       部门行不在此处收窄，由 {@link #departmentLevels} 只取本部门自身上报行；</li>
     *   <li>全量视角：不过滤。</li>
     * </ul>
     */
    private static List<AppraisalRow> filterRowsByScope(DashboardScope scope, List<AppraisalRow> rows) {
        if (scope.isFull()) {
            return rows;
        }
        List<AppraisalRow> result = new ArrayList<>(rows.size());
        for (AppraisalRow row : rows) {
            if (matchesScope(scope, row)) {
                result.add(row);
            }
        }
        return result;
    }

    private static boolean matchesScope(DashboardScope scope, AppraisalRow row) {
        Long orgId = scope.orgId();
        if (orgId == null) {
            return true;
        }
        if (scope.kind() == DashboardScope.Kind.COLLEGE) {
            // 学院视角：指标 target=本院（学院月报 target_org 恒等于 report_org）
            return row.targetOrgId() != null && row.targetOrgId().equals(orgId);
        }
        // 职能部门视角：本部门职能线 + 归口到本部门的学院线
        return Objects.equals(row.reportOrgId(), orgId)
                || (row.functionalOrgId() != null && row.functionalOrgId().equals(orgId));
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
     * 指定月份当月快照下的各职能部门三档计数（2026-11-18 拍板，2026-11-25 B2 修复归组键 + 灰柱底座）。
     *
     * <p><b>2026-11-27 部门行口径（用户拍板）</b>：「指标完成情况分布」是部门专用卡，部门行只统计
     * <b>部门自己上报的指标</b>——职能部门月报（report_org_type=职能部门，report_org=该部门）按
     * 上报组织归组；归口到该部门的学院线月报<b>不再并入部门行</b>，与下钻 {@code indicator-levels}
     * 的「部门自身」口径同屏对得上。全量视角与职能部门视角同此口径，所以职能部门视角里本部门
     * 那一行与全校视图里本部门行完全一致。学院视角例外：它的 departments 是「本院各归口线」
     * 的行（学院卡在用），学院线月报仍按归口部门归组。</p>
     *
     * <p>行集 = 当月上报归组部门 ∪ 当年有下发记录的部门（与学院卡 toCollegeLevel 同构）：
     * 上报部门按既有归口归组逻辑生成彩段/灰段；纯下发部门（当月无上报行）以
     * {@code reported=false / monthState=NOT_REPORTED / 三档全 0 / dispatchedCount=已下发数}
     * 长出灰柱行，供前端画「已下发 n 项·未填报」督办。</p>
     *
     * <ul>
     *   <li>只统计该月自己的行（report_month == month）；职能部门月报按 reportOrgId 归组；
     *       学院线月报仅在学院视角按 functionalOrgId 归到归口职能部门（归口为空跳过），
     *       全量/职能部门视角不进部门行；</li>
     *   <li>鉴定值只来自 APPROVED 月报；</li>
     *   <li>该月已填报但审批中（SUBMITTED/IN_REVIEW）的部门：鉴定值全 0，
     *       {@code monthState=IN_REVIEW}，仍返回（前端置灰标「审批中」）；</li>
     *   <li>当年有下发记录但当月未填报的部门：{@code monthState=NOT_REPORTED}，三档全 0，
     *       {@code reported=false}，灰柱高度取已下发数，仍返回；</li>
     *   <li>APPROVED 但当月没有任何已鉴定指标的部门仍不返回（无内容可画）；
     *       NOT_REPORTED 灰柱不受该规则影响（无鉴定值也要画灰柱）。</li>
     * </ul>
     *
     * <p>{@code dispatchByOrgId}：当年各组织下发计数行（orgId → (orgName, dispatchedCount)，
     * 口径对齐 CollegeLevelDTO.dispatchedCount）；无下发记录的部门计 0。
     * 归组键统一：上报行与下发行用同一 orgId，因此部门自身上报行与它的下发计数落在同一行
     * （灰段+彩段并存）。</p>
     */
    private static List<DepartmentLevelDTO> departmentLevels(
            List<AppraisalRow> rows, int month, Map<Long, DispatchCountRow> dispatchByOrgId,
            DashboardScope scope) {
        // 学院线月报只有学院视角按归口部门归组（departments = 本院各归口线）；
        // 全量/职能部门视角的部门行只认部门自己上报的月报
        boolean groupCollegeLinesByFunctionalOrg = scope.kind() == DashboardScope.Kind.COLLEGE;

        Map<Long, String> orgNames = new LinkedHashMap<>();
        Set<Long> reportedOrgIds = new HashSet<>();
        Map<Long, Boolean> approvedByOrg = new HashMap<>();
        Map<Long, Map<Long, String>> levelByOrgAndIndicator = new HashMap<>();

        for (AppraisalRow row : rows) {
            if (monthOf(row.reportMonth()) != month) {
                continue;
            }
            Long groupOrgId;
            String groupOrgName;
            if (isFunctionalOrgType(row.reportOrgType())) {
                // 职能部门月报：按上报组织自身归组（部门自身口径）
                groupOrgId = row.reportOrgId();
                groupOrgName = row.reportOrgName();
            } else {
                // 学院线月报：2026-11-27 起不再并入归口部门行，只服务学院视角的「本院各归口线」；
                // functionalOrgId 为空的学院行跳过（不能凭空归组）
                if (!groupCollegeLinesByFunctionalOrg || row.functionalOrgId() == null) {
                    continue;
                }
                groupOrgId = row.functionalOrgId();
                groupOrgName = row.functionalOrgName();
            }
            // orgNames / reportedOrgIds / approvedByOrg / levelByOrgAndIndicator 统一使用同一归组键
            orgNames.putIfAbsent(groupOrgId, groupOrgName);
            reportedOrgIds.add(groupOrgId);
            if (!isApproved(row.reportStatus())) {
                continue;
            }
            approvedByOrg.put(groupOrgId, Boolean.TRUE);
            Map<Long, String> levels =
                    levelByOrgAndIndicator.computeIfAbsent(groupOrgId, k -> new HashMap<>());
            // 同一指标同月多轮上报时保留行序靠后的一条（ORDER BY 保证行序即时间序）
            levels.put(row.indicatorId(), row.appraisalLevel());
        }

        // 2026-11-25 灰柱底座：并集并入当年有下发记录的部门。纯下发部门无上报行，
        // 部门名取下发计数行的 orgName（SQL 已带出）；已上报部门保留其上报行部门名。
        for (Map.Entry<Long, DispatchCountRow> dispatch : dispatchByOrgId.entrySet()) {
            orgNames.putIfAbsent(dispatch.getKey(), dispatch.getValue().orgName());
        }

        List<DepartmentLevelDTO> result = new ArrayList<>(orgNames.size());
        for (Map.Entry<Long, String> org : orgNames.entrySet()) {
            Long orgId = org.getKey();
            boolean reported = reportedOrgIds.contains(orgId);
            boolean approved = approvedByOrg.getOrDefault(orgId, Boolean.FALSE);
            MonthState monthState = !reported ? MonthState.NOT_REPORTED
                    : (approved ? MonthState.APPROVED : MonthState.IN_REVIEW);
            Map<Long, String> levels = approved
                    ? levelByOrgAndIndicator.getOrDefault(orgId, Map.of())
                    : Map.of();
            long ahead = countLevel(levels, LEVEL_AHEAD);
            long normal = countLevel(levels, LEVEL_NORMAL);
            long delayed = countLevel(levels, LEVEL_DELAYED);
            long total = ahead + normal + delayed;
            // APPROVED 但无任何已鉴定指标的部门不返回；IN_REVIEW 与 NOT_REPORTED 无鉴定值也要返回
            // （前端分别置灰标「审批中」/画「已下发 n 项·未填报」灰柱）
            if (total == 0 && monthState == MonthState.APPROVED) {
                continue;
            }
            DispatchCountRow dispatch = dispatchByOrgId.get(orgId);
            result.add(DepartmentLevelDTO.builder()
                    .orgId(orgId)
                    .orgName(org.getValue())
                    .ahead(ahead)
                    .normal(normal)
                    .delayed(delayed)
                    .total(total)
                    .dispatchedCount(dispatch == null ? 0L : dispatch.dispatchedCount())
                    .reported(reported)
                    .monthState(monthState)
                    .build());
        }
        result.sort(Comparator.comparing(DepartmentLevelDTO::getOrgName,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    /**
     * 当年各组织下发计数行（灰柱数据源）：orgId → (orgName, dispatchedCount)。
     *
     * <p>2026-11-27 角色分流：学院视角不取下发计数——departments 只由本院上报行
     * 归口生成，不并入全校灰柱底座（否则会混入全校各部门的行，且「全校归口部门
     * 已下发数」对学院视角无意义）；职能部门视角只保留本部门的下发计数（未填报月份
     * 的灰柱行仍按「已下发 n 项」出高度，与全校视图本部门行一致）。</p>
     */
    private Map<Long, DispatchCountRow> indexDispatchCounts(Integer year, DashboardScope scope) {
        if (scope.kind() == DashboardScope.Kind.COLLEGE) {
            return Map.of();
        }
        Map<Long, DispatchCountRow> byOrgId = new HashMap<>();
        for (DispatchCountRow row : appraisalSummaryQueryRepository.fetchDispatchCounts(year)) {
            if (scope.kind() == DashboardScope.Kind.FUNCTIONAL
                    && !Objects.equals(row.orgId(), scope.orgId())) {
                continue;
            }
            byOrgId.put(row.orgId(), row);
        }
        return byOrgId;
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
