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
    /** 自评进度等级 AHEAD/NORMAL/DELAYED（P1 上报链改造） */
    private String selfRating;
    /** 完成情况描述（与 comment 同源，读侧优先） */
    private String description;
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
                .selfRating(snapshot.selfRating())
                .description(snapshot.description())
                .attachments(attachmentResponses)
                .build();
    }
}
