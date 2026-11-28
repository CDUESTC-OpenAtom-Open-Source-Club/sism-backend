package com.sism.analytics.application;

import com.sism.analytics.domain.repository.CollegeLevelQueryRepository;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeDispatchCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelMonthlyCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeReportedMonthRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.DelayedIndicatorRow;
import com.sism.analytics.domain.repository.DashboardScopeQueryRepository;
import com.sism.analytics.interfaces.dto.CollegeLevelDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO.CollegeLevelMonthDTO;
import com.sism.analytics.interfaces.dto.DelayedIndicatorsResponseDTO;
import com.sism.analytics.interfaces.dto.DelayedIndicatorsResponseDTO.DelayedIndicatorDTO;
import com.sism.analytics.interfaces.dto.MonthState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * CollegeLevelDashboardService - 学院指标完成情况/月度趋势/滞后任务清单聚合服务。
 *
 * <p>2026-11-18 用户拍板口径（「按月快照 + 未出结果置灰」，升级自 2026-11-17 版）：
 * 数据看板改由月报真实数据聚合——</p>
 * <ul>
 *   <li><b>只有 APPROVED 月报计入鉴定值</b>（按月快照：填报了但审批未通过/审批中的月份
 *       不出等级柱，置灰处理；DRAFT/REJECTED 不计入）</li>
 *   <li>审批中（SUBMITTED/IN_REVIEW）月份在响应中带 {@code monthState=IN_REVIEW}，
 *       供前端置灰显示「审批中」；未填报月份 monthState=NOT_REPORTED</li>
 *   <li>等级优先级 appraisal_level &gt; self_rating</li>
 *   <li>未填报学院也要返回（前端画灰柱「下发 n 项·未填报」督办）</li>
 *   <li>每学院附带已下发/未下发指标数（按年）</li>
 *   <li>滞后清单不设组织类型过滤：职能部门线 + 学院线全量记录（org 语义=「责任单位」）</li>
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
    private final DashboardScopeQueryRepository dashboardScopeQueryRepository;

    public CollegeLevelDashboardService(
            CollegeLevelQueryRepository collegeLevelQueryRepository,
            DashboardScopeQueryRepository dashboardScopeQueryRepository) {
        this.collegeLevelQueryRepository = collegeLevelQueryRepository;
        this.dashboardScopeQueryRepository = dashboardScopeQueryRepository;
    }

    /**
     * 获取指定月份各学院鉴定等级计数（按月快照口径）。
     * 学院集合 = 当年有指标（已下发/未下发）的学院 ∪ 当月已上报学院；
     * 只有 APPROVED 月报计入等级计数，审批中月份 monthState=IN_REVIEW（鉴定值为 0），
     * 未填报学院（当月无已提交/审批中/已审批月报）同样返回，
     * monthState=NOT_REPORTED 供前端画灰柱督办。
     */
    public CollegeLevelsResponseDTO getCollegeLevels(Integer year, Integer month) {
        return getCollegeLevels(year, month, DashboardScope.full());
    }

    /**
     * 指定视角的同口径聚合（2026-11-27 角色分流）。
     *
     * <p>学院视角只返回本院一行（总览统计/等级分布随之收敛为本院数据）；
     * 职能部门视角只返回「本部门有下发关联」的学院——学院侧存在归口=本部门的线
     * （学院线指标 → sys_task → plan.functional_org_id=本部门）才可见（2026-11-27 拍板）；
     * 全量视角维持全校学院列表。</p>
     */
    public CollegeLevelsResponseDTO getCollegeLevels(Integer year, Integer month, DashboardScope scope) {
        requireYear(year);
        requireMonth(month);
        DashboardScope effectiveScope = scope == null ? DashboardScope.full() : scope;
        String reportMonth = toReportMonth(year, month);

        Map<Long, CollegeDispatchCountRow> dispatchByOrgId = indexDispatchCounts(year);
        Map<Long, CollegeReportedMonthRow> reportedByOrgId =
                collectReportedOrgsByMonth(year, month, month);
        Map<Long, CollegeLevelCountRow> levelsByOrgId = indexLevelCounts(reportMonth);

        Set<Long> orgIds = new TreeSet<>(dispatchByOrgId.keySet());
        orgIds.addAll(reportedByOrgId.keySet());

        Set<Long> visibleColleges = linkedCollegesOrNull(effectiveScope);
        List<CollegeLevelDTO> colleges = new ArrayList<>(orgIds.size());
        for (Long orgId : orgIds) {
            if (!matchesScope(effectiveScope, orgId) || !isVisibleCollege(visibleColleges, orgId)) {
                continue;
            }
            colleges.add(toCollegeLevel(
                    orgId, month, dispatchByOrgId, levelsByOrgId, reportedByOrgId.get(orgId)));
        }
        return CollegeLevelsResponseDTO.builder()
                .year(year)
                .month(month)
                .colleges(colleges)
                .build();
    }

    /** 学院视角按本院 orgId 收敛行集合；全量/职能部门视角不收敛 */
    private static boolean matchesScope(DashboardScope scope, Long orgId) {
        return scope.isFull() || scope.kind() != DashboardScope.Kind.COLLEGE
                || scope.orgId().equals(orgId);
    }

    /**
     * 职能部门视角的可见学院集合（2026-11-27 拍板：只能看到本部门已下发指标关联的学院）；
     * 学院/全量视角返回 null（不额外收敛）。
     */
    private Set<Long> linkedCollegesOrNull(DashboardScope scope) {
        if (scope.kind() != DashboardScope.Kind.FUNCTIONAL) {
            return null;
        }
        return new HashSet<>(dashboardScopeQueryRepository.findLinkedCollegeIds(scope.orgId()));
    }

    /** 职能部门视角按关联学院集合收敛；集合为 null 表示不收敛（学院/全量视角） */
    private static boolean isVisibleCollege(Set<Long> visibleColleges, Long orgId) {
        return visibleColleges == null || visibleColleges.contains(orgId);
    }

    /**
     * 获取年度趋势。固定返回 1-12 月全量月份（升序，逐月独立快照，互不沿用），
     * 每月学院集合 = 当年有指标的学院 ∪ 当月已上报学院；口径同 {@link #getCollegeLevels}。
     */
    public CollegeLevelTrendResponseDTO getCollegeLevelsTrend(Integer year) {
        return getCollegeLevelsTrend(year, DashboardScope.full());
    }

    /**
     * 指定视角的年度趋势（2026-11-27 角色分流）：学院视角逐月只返回本院一行，
     * 职能部门视角逐月只返回「本部门有下发关联」的学院，其余视角固定 1-12 月全量学院，
     * 口径同 {@link #getCollegeLevels}。
     */
    public CollegeLevelTrendResponseDTO getCollegeLevelsTrend(Integer year, DashboardScope scope) {
        requireYear(year);
        DashboardScope effectiveScope = scope == null ? DashboardScope.full() : scope;
        Map<Long, CollegeDispatchCountRow> dispatchByOrgId = indexDispatchCounts(year);
        Map<Integer, Map<Long, CollegeReportedMonthRow>> reportedByMonthAndOrg =
                collectReportedOrgsByMonth(year);
        Map<Integer, Map<Long, CollegeLevelCountRow>> levelsByMonthAndOrg =
                indexMonthlyLevelCounts(year);

        Set<Long> visibleColleges = linkedCollegesOrNull(effectiveScope);
        List<CollegeLevelMonthDTO> months = new ArrayList<>(MAX_MONTH);
        for (int month = MIN_MONTH; month <= MAX_MONTH; month++) {
            Map<Long, CollegeReportedMonthRow> reportedByOrgId =
                    reportedByMonthAndOrg.getOrDefault(month, Map.of());
            Set<Long> orgIds = new TreeSet<>(dispatchByOrgId.keySet());
            orgIds.addAll(reportedByOrgId.keySet());

            Map<Long, CollegeLevelCountRow> levelsForMonth =
                    levelsByMonthAndOrg.getOrDefault(month, Map.of());
            List<CollegeLevelDTO> colleges = new ArrayList<>(orgIds.size());
            for (Long orgId : orgIds) {
                if (!matchesScope(effectiveScope, orgId) || !isVisibleCollege(visibleColleges, orgId)) {
                    continue;
                }
                colleges.add(toCollegeLevel(
                        orgId, month, dispatchByOrgId, levelsForMonth, reportedByOrgId.get(orgId)));
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
     * 2026-11-18 拍板：职能部门线 + 学院线全量记录（org 语义=「责任单位」）；
     * 月报只认 APPROVED（按月快照口径，审批中月份无鉴定值）。
     * 同一指标多轮已审批上报时按接口排序取首条去重，避免重复催办。
     */
    public DelayedIndicatorsResponseDTO getDelayedIndicators(Integer year, Integer month) {
        return getDelayedIndicators(year, month, DashboardScope.full());
    }

    /**
     * 指定视角的滞后指标清单（2026-11-27 角色分流）：
     * 学院视角只保留责任单位=本院的行；职能部门视角保留责任单位=本部门的行，
     * 外加「本部门有下发关联」学院（学院侧存在归口=本部门的线）的学院线行——
     * 归口部门的催办场景需要看到自己线上的学院滞后（2026-11-27 拍板）；
     * 全量视角不过滤。行 orgId 语义为「责任单位」=指标 target 组织。
     */
    public DelayedIndicatorsResponseDTO getDelayedIndicators(
            Integer year, Integer month, DashboardScope scope) {
        requireYear(year);
        requireMonth(month);
        DashboardScope effectiveScope = scope == null ? DashboardScope.full() : scope;
        String reportMonth = toReportMonth(year, month);

        Set<Long> visibleColleges = linkedCollegesOrNull(effectiveScope);
        Map<Long, DelayedIndicatorDTO> byIndicatorId = new LinkedHashMap<>();
        for (DelayedIndicatorRow row : collegeLevelQueryRepository.fetchDelayedIndicators(reportMonth)) {
            if (!matchesDelayedScope(effectiveScope, visibleColleges, row.orgId())) {
                continue;
            }
            byIndicatorId.computeIfAbsent(row.indicatorId(), indicatorId -> toDelayedIndicator(row));
        }
        List<DelayedIndicatorDTO> items = List.copyOf(byIndicatorId.values());
        return DelayedIndicatorsResponseDTO.builder()
                .year(year)
                .month(month)
                .items(items)
                .build();
    }

    /**
     * 学院/职能部门视角按责任单位（指标 target 组织）收敛；全量视角不收敛。
     * 职能部门视角额外放行「本部门有下发关联」的学院行（visibleColleges）。
     */
    private static boolean matchesDelayedScope(DashboardScope scope, Set<Long> visibleColleges, Long rowOrgId) {
        if (scope.isFull()) {
            return true;
        }
        if (scope.orgId() != null && scope.orgId().equals(rowOrgId)) {
            return true;
        }
        return scope.kind() == DashboardScope.Kind.FUNCTIONAL
                && visibleColleges != null && visibleColleges.contains(rowOrgId);
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    /**
     * 组装单学院行（2026-11-18 按月快照口径）：
     * 等级计数只来自 APPROVED 月报（levelsByOrgId）；审批中/未填报月份等级计数全 0，
     * 由 reported 行的 monthState 区分（APPROVED / IN_REVIEW / NOT_REPORTED）供前端置灰。
     */
    private static CollegeLevelDTO toCollegeLevel(
            Long orgId,
            int month,
            Map<Long, CollegeDispatchCountRow> dispatchByOrgId,
            Map<Long, CollegeLevelCountRow> levelsByOrgId,
            CollegeReportedMonthRow reportedRow
    ) {
        CollegeDispatchCountRow dispatch = dispatchByOrgId.get(orgId);
        CollegeLevelCountRow levels = levelsByOrgId.get(orgId);
        boolean reported = reportedRow != null;
        MonthState monthState = reported
                ? toMonthState(reportedRow.monthState())
                : MonthState.NOT_REPORTED;
        return CollegeLevelDTO.builder()
                .orgId(orgId)
                .orgName(resolveOrgName(orgId, dispatch, levels, reported ? reportedRow.orgName() : null))
                .month(month)
                .ahead(levels == null ? 0L : levels.ahead())
                .normal(levels == null ? 0L : levels.normal())
                .delayed(levels == null ? 0L : levels.delayed())
                .total(levels == null ? 0L : levels.total())
                .dispatchedCount(dispatch == null ? 0L : dispatch.dispatchedCount())
                .undispatchedCount(dispatch == null ? 0L : dispatch.undispatchedCount())
                .reported(reported)
                .monthState(monthState)
                .build();
    }

    /** 月报状态落库值（APPROVED / IN_REVIEW，由 reported-months SQL 归一）映射为契约枚举 */
    private static MonthState toMonthState(String monthState) {
        if (MonthState.APPROVED.name().equals(monthState)) {
            return MonthState.APPROVED;
        }
        return MonthState.IN_REVIEW;
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

    /** 单月：orgId → 已上报月报行（含 monthState） */
    private Map<Long, CollegeReportedMonthRow> collectReportedOrgsByMonth(Integer year, int startMonth, int endMonth) {
        Map<Long, CollegeReportedMonthRow> byOrgId = new HashMap<>();
        for (CollegeReportedMonthRow row : collegeLevelQueryRepository.fetchCollegeReportedMonths(
                toReportMonth(year, startMonth), toReportMonth(year, endMonth))) {
            byOrgId.putIfAbsent(row.orgId(), row);
        }
        return byOrgId;
    }

    /** 全年：month → (orgId → 已上报月报行) */
    private Map<Integer, Map<Long, CollegeReportedMonthRow>> collectReportedOrgsByMonth(Integer year) {
        Map<Integer, Map<Long, CollegeReportedMonthRow>> byMonth = new TreeMap<>();
        for (CollegeReportedMonthRow row : collegeLevelQueryRepository
                .fetchCollegeReportedMonths(
                        toReportMonth(year, MIN_MONTH), toReportMonth(year, MAX_MONTH))) {
            byMonth.computeIfAbsent(row.month(), ignored -> new HashMap<>())
                    .putIfAbsent(row.orgId(), row);
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
