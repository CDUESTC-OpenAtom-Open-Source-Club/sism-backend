package com.sism.execution.domain.report;

/**
 * 单指标填报明细快照。
 * P1 上报链改造新增：selfRating（自评进度等级）与 description（完成情况描述）。
 */
public record PlanReportIndicatorSnapshot(
        Long indicatorId,
        Integer progress,
        String comment,
        String selfRating,
        String description,
        java.util.List<PlanReportAttachmentSnapshot> attachments
) {
}
