package com.sism.analytics.domain.repository;

import java.util.List;

/**
 * CollegeLevelQueryRepository - 学院指标完成情况（月报鉴定等级）聚合查询接口。
 *
 * <p>口径（2026-11-18 用户拍板「按月快照 + 未出结果置灰」，升级自 2026-11-17 版）：</p>
 * <ul>
 *   <li>只有 APPROVED 月报计入鉴定值（按月快照：SUBMITTED/IN_REVIEW 月报是「审批中」，
 *       不出等级计数；DRAFT/REJECTED 不计入）</li>
 *   <li>等级优先级 appraisal_level &gt; self_rating：取
 *       COALESCE(NULLIF(appraisal_level,''), NULLIF(self_rating,''))（AHEAD/NORMAL/DELAYED），
 *       两级均为 NULL 的明细行不计数</li>
 *   <li>等级计数/月度趋势仍只统计 org.type='academic'（二级学院）的指标目标组织；
 *       滞后清单不设组织类型过滤——职能部门线（指标 target=职能部门）的滞后同样记录
 *       （2026-11-18 拍板），org 语义为「责任单位」</li>
 *   <li>已上报状态按月标记 monthState（APPROVED/IN_REVIEW）：未填报学院也要返回（灰柱数据），
 *       学院集合 = 当年有指标（下发/未下发）的学院 ∪ 当月已上报学院，未填报标记 reported=false</li>
 *   <li>每学院附带已下发/未下发指标数（按年，经 indicator → sys_task → plan → cycle.year）</li>
 *   <li>手动预警（alert_event）不参与本聚合</li>
 * </ul>
 */
public interface CollegeLevelQueryRepository {

    /**
     * 聚合指定月份（reportMonth 格式 yyyyMM）各学院的鉴定等级计数。
     * 仅返回当月有可计数等级明细的学院。
     */
    List<CollegeLevelCountRow> fetchCollegeLevelCounts(String reportMonth);

    /**
     * 聚合指定月份区间（格式 yyyyMM，含首尾）内各月份、各学院的鉴定等级计数。
     * 仅返回有可计数等级明细的「月份 × 学院」组合；月份由 SQL 从 report_month 截取。
     */
    List<CollegeLevelMonthlyCountRow> fetchCollegeLevelMonthlyCounts(String startMonth, String endMonth);

    /**
     * 按年统计各学院的已下发/未下发指标数（灰柱「下发 n 项·未填报」与未下发数的数据源）。
     * 年份经 indicator → sys_task → plan → cycle.year 关系链过滤，与指标列表按年口径一致。
     */
    List<CollegeDispatchCountRow> fetchCollegeDispatchCounts(Integer year);

    /**
     * 查询月份区间内「已填报」月报覆盖的（月份 × 学院）组合及该月状态，
     * DRAFT/REJECTED 月报不算已上报。同 org 同月多轮上报时状态取优先级
     * APPROVED &gt; IN_REVIEW（SUBMITTED/IN_REVIEW）。用于未填报标记 reported
     * 与审批中标记 monthState（2026-11-18 按月快照口径）。
     */
    List<CollegeReportedMonthRow> fetchCollegeReportedMonths(String startMonth, String endMonth);

    /**
     * 查询指定月份鉴定为 DELAYED（appraisal_level 优先，self_rating 兜底）的指标明细清单，
     * 供看板「滞后任务响应清单」卡片与催办使用。
     *
     * <p>2026-11-18 拍板：不设指标目标组织类型过滤——职能部门线（target=职能部门）与
     * 学院线（target=学院）的滞后都记录；orgId/orgName 语义为「责任单位」
     * （职能部门线的 org=该职能部门，学院线的 org=学院）。</p>
     */
    List<DelayedIndicatorRow> fetchDelayedIndicators(String reportMonth);

    record CollegeLevelCountRow(
            long orgId,
            String orgName,
            long ahead,
            long normal,
            long delayed,
            long total
    ) {
    }

    record CollegeLevelMonthlyCountRow(
            int month,
            long orgId,
            String orgName,
            long ahead,
            long normal,
            long delayed,
            long total
    ) {
    }

    record CollegeDispatchCountRow(
            long orgId,
            String orgName,
            long dispatchedCount,
            long undispatchedCount
    ) {
    }

    /**
     * 已填报（月份 × 学院）行。
     *
     * @param monthState 该月该学院的月报状态：APPROVED（有已审批月报，鉴定值有效）
     *                   / IN_REVIEW（仅有 SUBMITTED/IN_REVIEW 月报，审批中，无鉴定值）
     */
    record CollegeReportedMonthRow(
            int month,
            long orgId,
            String orgName,
            String monthState
    ) {
    }

    /**
     * 滞后指标明细行。orgId/orgName 为「责任单位」（指标 target 组织：
     * 职能部门线即该职能部门，学院线即学院）。
     */
    record DelayedIndicatorRow(
            long indicatorId,
            String indicatorName,
            long orgId,
            String orgName,
            String ownerOrgName,
            String selfRating,
            String appraisalLevel
    ) {
    }
}
