package com.sism.shared.domain.indicator;

/**
 * 审核进度同步端口（2026-10-28 用户定案）。
 *
 * 审批链上的「审核进度」以 {@code plan_report_indicator.appraisal_level} 为准；
 * 看板/列表/预警仍读取 Alert 侧的手动等级。两者必须同步，否则会出现
 * 「审批里改了等级、列表还是旧值」的分裂。
 *
 * 实现方（sism-alert）做尽力而为同步；同步失败不回滚审批数据。
 */
public interface IndicatorAppraisalLevelPort {

    /**
     * @param indicatorId 指标 ID
     * @param level       AHEAD / NORMAL / DELAYED；null 表示恢复未评定
     * @param handledBy   操作人 ID
     */
    void syncManualLevel(Long indicatorId, String level, Long handledBy);
}
