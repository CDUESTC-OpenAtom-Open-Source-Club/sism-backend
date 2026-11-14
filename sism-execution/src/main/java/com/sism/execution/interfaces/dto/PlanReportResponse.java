package com.sism.execution.interfaces.dto;

import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.ReportOrgType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * PlanReportResponse - 计划报告响应DTO
 * 用于返回计划报告的完整信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanReportResponse {

    private Long id;
    private String reportMonth;
    private Long reportOrgId;
    private ReportOrgType reportOrgType;
    private Long planId;
    /** 2026-11-13：报告归口职能部门（学院上报按归口部门分批提交；空=旧口径） */
    private Long functionalOrgId;
    private Long auditInstanceId;
    private String title;
    private String content;
    private String summary;
    private Integer progress;
    private String issues;
    private String nextPlan;
    private String status;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String rejectionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<PlanReportIndicatorDetailResponse> indicatorDetails;

    /**
     * 从实体转换为响应DTO
     */
    public static PlanReportResponse fromEntity(PlanReport report) {
        List<PlanReportIndicatorDetailResponse> indicatorDetailResponses = report.getIndicatorDetails() == null
                ? Collections.emptyList()
                : report.getIndicatorDetails().stream()
                .filter(java.util.Objects::nonNull)
                .map(PlanReportIndicatorDetailResponse::fromSnapshot)
                .toList();
        return PlanReportResponse.builder()
                .id(report.getId())
                .reportMonth(report.getReportMonth())
                .reportOrgId(report.getReportOrgId())
                .reportOrgType(report.getReportOrgType())
                .planId(report.getPlanId())
                .functionalOrgId(report.getFunctionalOrgId())
                .auditInstanceId(report.getAuditInstanceId())
                .title(report.getTitle())
                .content(report.getContent())
                .summary(report.getSummary())
                .progress(report.getProgress())
                .issues(report.getIssues())
                .nextPlan(report.getNextPlan())
                .status(report.getStatus())
                .submittedBy(report.getSubmittedBy())
                .submittedAt(report.getSubmittedAt())
                .approvedBy(report.getApprovedBy())
                .approvedAt(report.getApprovedAt())
                .rejectionReason(report.getRejectionReason())
                .createdAt(report.getCreatedAt())
                .updatedAt(report.getUpdatedAt())
                .indicatorDetails(indicatorDetailResponses)
                .build();
    }
}
