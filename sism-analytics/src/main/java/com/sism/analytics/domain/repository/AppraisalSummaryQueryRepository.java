package com.sism.analytics.domain.repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * AppraisalSummaryQueryRepository - 数据看板「审核鉴定」口径的原始数据查询。
 *
 * <p>背景（2026-11-17 用户反馈第 1 条）：职能部门指标填报审核完成后，数据看板里的
 * 「指标完成情况分布」「TOP 滞后任务响应清单」「任务完成简报」都不随审核结果变化。</p>
 *
 * <p>根因是前端拿 {@code strategicStore.indicators} 的 workflow status 兜底计算，
 * 而审核结果只落在 {@code plan_report_indicator.appraisal_level} 上。本仓库只负责把
 * 「已鉴定的上报明细」原样捞出来（一份年度明细），聚合口径放在
 * {@link com.sism.analytics.application.AppraisalSummaryService}，便于单测与换库。</p>
 *
 * <p>口径（与 CollegeLevelQueryRepository 保持一致）：</p>
 * <ul>
 *   <li>只算已提交/已审批的月报（DRAFT/REJECTED 不计入）</li>
 *   <li>只看 plan_report_indicator.appraisal_level（AHEAD/NORMAL/DELAYED），
 *       self_rating 不参与</li>
 *   <li>只算 org.status='DISTRIBUTED' 且未删除的指标</li>
 *   <li>手动预警（alert_event）不参与</li>
 * </ul>
 */
public interface AppraisalSummaryQueryRepository {

    /**
     * 查询指定月份区间（reportMonth 格式 yyyyMM，含首尾）内所有已鉴定的上报明细。
     * 一行 = 一条「组织上报某指标某月被鉴定为某等级」。
     */
    List<AppraisalRow> fetchAppraisalRows(String startMonth, String endMonth);

    /**
     * 已鉴定上报明细行。
     *
     * @param reportOrgId      上报组织 ID（月报的 report_org_id）
     * @param reportOrgName    上报组织名称
     * @param reportOrgType    上报组织类型（sys_org.type 的文本值：admin/functional/academic）
     * @param reportMonth      上报月份，格式 yyyyMM
     * @param indicatorId      指标 ID
     * @param indicatorName    指标名称（indicator.indicator_desc）
     * @param targetOrgId      指标目标组织 ID（即「责任主体」）
     * @param targetOrgName    指标目标组织名称
     * @param appraisalLevel   上级鉴定等级（AHEAD/NORMAL/DELAYED）
     * @param weightPercent    指标权重（用于滞后任务排序，可为 null）
     */
    record AppraisalRow(
            long reportOrgId,
            String reportOrgName,
            String reportOrgType,
            String reportMonth,
            long indicatorId,
            String indicatorName,
            Long targetOrgId,
            String targetOrgName,
            String appraisalLevel,
            BigDecimal weightPercent
    ) {
    }
}
