package com.sism.execution.interfaces.dto;

import com.sism.execution.domain.report.PlanReportIndicatorSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Collections;

/**
 * 报告中单个指标填报明细响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanReportIndicatorDetailResponse {

    private Long indicatorId;
    private Integer progress;
    private String comment;
    private List<ReportAttachmentResponse> attachments;

    public static PlanReportIndicatorDetailResponse fromSnapshot(PlanReportIndicatorSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        List<ReportAttachmentResponse> attachmentResponses = snapshot.attachments() == null
                ? Collections.emptyList()
                : snapshot.attachments().stream()
                .filter(java.util.Objects::nonNull)
                .map(attachment -> ReportAttachmentResponse.builder()
                        .id(attachment.id())
                        .fileName(attachment.fileName())
                        .fileSize(attachment.fileSize())
                        .fileType(attachment.fileType())
                        .url(attachment.url())
                        .uploadedBy(attachment.uploadedBy())
                        .uploadedAt(attachment.uploadedAt())
                        .build())
                .toList();
        return PlanReportIndicatorDetailResponse.builder()
                .indicatorId(snapshot.indicatorId())
                .progress(snapshot.progress())
                .comment(snapshot.comment())
                .attachments(attachmentResponses)
                .build();
    }
}
