package com.sism.shared.domain.workflow;

import java.util.Optional;

/**
 * Shared business context lookup for workflow decisions.
 */
public interface WorkflowBusinessContextPort {

    record BusinessSummary(
            Long planId,
            String planName,
            Long sourceOrgId,
            String sourceOrgName,
            Long targetOrgId,
            String targetOrgName,
            String displayName
    ) {}

    Optional<Long> getPlanIdByEntity(String entityType, Long entityId);

    Optional<BusinessSummary> getBusinessSummary(String entityType, Long entityId);

    /**
     * P1 上报链改造：审批携带审核进度时，投影到业务明细行。
     * 默认空实现；仅关心该实体类型的上下文适配器覆写。
     *
     * @deprecated 2026-10-24 用户定案：整体鉴定废弃，改用 {@link #applyAppraisalLevelForIndicators}。
     *             仅为兼容保留。
     */
    @Deprecated
    default void applyAppraisalLevel(String entityType, Long entityId, String appraisalLevel) {
    }

    /**
     * 2026-10-24 用户定案（指标级鉴定）：审批通过携带逐指标等级时，
     * 投影到该报告对应明细行（entityId=报告ID，indicatorLevels=指标ID→等级）。
     * 默认空实现；仅关心该实体类型的上下文适配器覆写。
     */
    default void applyAppraisalLevelForIndicators(String entityType, Long entityId,
                                                  java.util.Map<Long, String> indicatorLevels) {
    }

    /**
     * P5 指标异动：审批携带审核进度时（或异动发起/结束时）的业务侧通知钩子。
     */
    default void applyAppraisalLevelBatch(String entityType, java.util.List<Long> entityIds, String appraisalLevel) {
    }

    /**
     * P5 指标异动：异动审批到达终态（通过/驳回）后，清除指标的异动锁标记。
     */
    default void endIndicatorMutation(String entityType, Long entityId, boolean approved) {
    }

    /**
     * P5 指标异动：该组织是否因存在异动审批而被全面锁死（填报/提交）。
     */
    default boolean isOrgLockedByMutation(Long orgId) {
        return false;
    }

    /**
     * 2026-10-27 用户定案：上报链最后一个节点通过前，必须校验本报告实例下
     * 全部指标的审核进度已评定（不允许「未评定」通过）。
     * 本方法返回该业务实体（entityId=报告ID）下全部指标 ID，供审批侧做完整性校验。
     * 默认空列表；仅报告类实体（PLAN_REPORT）的上下文适配器覆写。
     */
    default java.util.List<Long> findIndicatorIdsByReport(Long entityId) {
        return java.util.List.of();
    }

    /**
     * 2026-10-28 用户定案：读取该报告轮次下已评定的逐指标审核进度。
     * 审批侧用它与本次提交的等级合并，实现「未改动沿用上一级、改动覆盖」。
     */
    default java.util.Map<Long, String> findIndicatorAppraisalLevels(Long entityId) {
        return java.util.Map.of();
    }

    /**
     * 2026-10-30 用户定案：审批拦截文案要报指标名称、不报指标 ID（老板看不懂数字行列）——
     * 返回该业务实体（entityId=报告ID）下 指标ID→指标名称（indicator_desc）映射。
     * 默认空映射；仅报告类实体（PLAN_REPORT）的上下文适配器覆写。
     */
    default java.util.Map<Long, String> findIndicatorNamesByReport(Long entityId) {
        return java.util.Map.of();
    }

    /**
     * 2026-10-30 用户定案：审批拦截文案要讲清楚去哪里操作——
     * 返回该报告的填报组织名称（如「XX学院」）。默认空；仅报告类实体的上下文适配器覆写。
     */
    default java.util.Optional<String> findReportOrgName(Long entityId) {
        return java.util.Optional.empty();
    }
}
