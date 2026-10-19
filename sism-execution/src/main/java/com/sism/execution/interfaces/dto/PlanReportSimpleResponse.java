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
 * PlanReportSimpleResponse - 计划报告简单响应DTO
 * 用于列表查询等场景，返回精简的报告信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanReportSimpleResponse {

    private Long id;
    private String reportMonth;
    private Long reportOrgId;
    private ReportOrgType reportOrgType;
    private Long auditInstanceId;
    private String title;
    private Integer progress;
    private String status;
    /** 2026-10-19：上报审批卡片要素——提交人ID/提交时间是数据库固定字段（markSubmitted 写入） */
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<PlanReportIndicatorDetailResponse> indicatorDetails;

    /**
     * 从实体转换为简单响应DTO
     */
    public static PlanReportSimpleResponse fromEntity(PlanReport report) {
        List<PlanReportIndicatorDetailResponse> indicatorDetailResponses =
                report.getIndicatorDetails() == null
                        ? Collections.emptyList()
                        : report.getIndicatorDetails().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(PlanReportIndicatorDetailResponse::fromSnapshot)
                        .toList();
        return PlanReportSimpleResponse.builder()
                .id(report.getId())
                .reportMonth(report.getReportMonth())
                .reportOrgId(report.getReportOrgId())
                .reportOrgType(report.getReportOrgType())
                .auditInstanceId(report.getAuditInstanceId())
                .title(report.getTitle())
                .progress(report.getProgress())
                .status(report.getStatus())
                .submittedBy(report.getSubmittedBy())
                .submittedAt(report.getSubmittedAt())
                .createdAt(report.getCreatedAt())
                .updatedAt(report.getUpdatedAt())
                .indicatorDetails(indicatorDetailResponses)
                .build();
    }
}
