package com.sism.analytics.domain.repository;

import java.util.List;

/**
 * IndicatorLevelQueryRepository - 行级「鉴定明细」查询（2026-11-27 新增）。
 *
 * <p>供看板下钻明细面板使用：按（责任单位，月份）返回指标级的鉴定行
 * （指标 id、名称、self_rating、appraisal_level）。口径与聚合一致——</p>
 * <ul>
 *   <li>只有 APPROVED 月报计入（SUBMITTED/IN_REVIEW 是审批中，无鉴定值）</li>
 *   <li>鉴定优先自评：effective level = COALESCE(appraisal_level, self_rating)</li>
 *   <li>只算 org.status='DISTRIBUTED' 且未删除的指标</li>
 *   <li>行主体 = 指标 target 组织（责任单位口径）：职能部门线（target=职能部门）
 *       与学院线（target=学院）都返回；「归口到某职能部门的学院线」target=学院，
 *       不会混入该职能部门的查询结果（与下钻明细面板「部门自身」口径一致）</li>
 * </ul>
 */
public interface IndicatorLevelQueryRepository {

    /**
     * 查询月份区间（reportMonth 格式 yyyyMM，含首尾）内的鉴定明细行。
     *
     * @param targetOrgId 责任单位组织 ID；null = 不限组织（全量视角）
     */
    List<IndicatorLevelRow> fetchIndicatorLevels(String startMonth, String endMonth, Long targetOrgId);

    /**
     * 行级鉴定明细。
     *
     * @param selfRating     自评等级（AHEAD/NORMAL/DELAYED；可能为 null）
     * @param appraisalLevel 上级鉴定等级（AHEAD/NORMAL/DELAYED；可能为 null）
     */
    record IndicatorLevelRow(
            long indicatorId,
            String indicatorName,
            String reportMonth,
            String selfRating,
            String appraisalLevel
    ) {
    }
}
