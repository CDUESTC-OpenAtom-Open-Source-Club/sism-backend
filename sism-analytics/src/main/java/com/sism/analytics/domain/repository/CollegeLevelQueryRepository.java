package com.sism.analytics.domain.repository;

import java.util.List;

/**
 * CollegeLevelQueryRepository - 学院指标完成情况（月报鉴定等级）聚合查询接口。
 *
 * <p>口径（2026-11-17 用户拍板，升级自 2026-10-31 版）：</p>
 * <ul>
 *   <li>只算已提交/已审批的月报（DRAFT/REJECTED 不计入；IN_REVIEW 是 SUBMITTED 的
 *       legacy 落库别名，一并按已提交口径计入）</li>
 *   <li>等级优先级 appraisal_level &gt; self_rating：取
 *       COALESCE(NULLIF(appraisal_level,''), NULLIF(self_rating,''))（AHEAD/NORMAL/DELAYED），
 *       两级均为 NULL 的明细行不计数</li>
 *   <li>只统计 org.type='academic'（二级学院）的指标目标组织</li>
 *   <li>未填报学院也要返回（灰柱数据）：学院集合 = 当年有指标（下发/未下发）的学院
 *       ∪ 当月已上报学院，未填报标记 reported=false</li>
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
     * 查询月份区间内「已提交/已审批」月报覆盖的（月份 × 学院）组合，
     * DRAFT/REJECTED 月报不算已上报。用于未填报标记 reported。
     */
    List<CollegeReportedMonthRow> fetchCollegeReportedMonths(String startMonth, String endMonth);

    /**
     * 查询指定月份鉴定为 DELAYED（appraisal_level 优先，self_rating 兜底）的指标明细清单，
     * 供看板「滞后任务响应清单」卡片与催办使用。
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

    record CollegeReportedMonthRow(
            int month,
            long orgId,
            String orgName
    ) {
    }

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
