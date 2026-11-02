package com.sism.analytics.domain.repository;

import java.util.List;

/**
 * CollegeLevelQueryRepository - 学院指标完成情况（月报鉴定等级）聚合查询接口。
 *
 * <p>口径（2026-10-31 用户拍板）：</p>
 * <ul>
 *   <li>只算已提交/已审批的月报（DRAFT/REJECTED 不计入）</li>
 *   <li>等级只看上级鉴定 plan_report_indicator.appraisal_level（AHEAD/NORMAL/DELAYED），
 *       self_rating 不纳入；appraisal_level 为 NULL 的明细行不计数</li>
 *   <li>只统计 org.type='academic'（二级学院）的指标目标组织</li>
 *   <li>未上报（当月无已鉴定上报记录）的学院不返回</li>
 *   <li>手动预警（alert_event）不参与本聚合</li>
 * </ul>
 */
public interface CollegeLevelQueryRepository {

    /**
     * 聚合指定月份（reportMonth 格式 yyyyMM）各学院的鉴定等级计数。
     * 仅返回当月有计数 &gt; 0 的学院。
     */
    List<CollegeLevelCountRow> fetchCollegeLevelCounts(String reportMonth);

    /**
     * 聚合指定月份区间（格式 yyyyMM，含首尾）内各月份、各学院的鉴定等级计数。
     * 仅返回有计数 &gt; 0 的「月份 × 学院」组合；月份由 SQL 从 report_month 截取。
     */
    List<CollegeLevelMonthlyCountRow> fetchCollegeLevelMonthlyCounts(String startMonth, String endMonth);

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
}
