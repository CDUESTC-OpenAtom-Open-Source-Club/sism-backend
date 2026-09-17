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
     * P1 上报链改造：审批携带鉴定进度等级时，投影到业务明细行。
     * 默认空实现；仅关心该实体类型的上下文适配器覆写。
     */
    default void applyAppraisalLevel(String entityType, Long entityId, String appraisalLevel) {
    }

    /**
     * P5 指标异动：审批携带鉴定等级时（或异动发起/结束时）的业务侧通知钩子。
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
}
