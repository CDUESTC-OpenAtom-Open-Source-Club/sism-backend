package com.sism.execution.domain.report;

public record PlanReportIndicatorSnapshot(
        Long indicatorId,
        Integer progress,
        String comment,
        java.util.List<PlanReportAttachmentSnapshot> attachments
) {
}
