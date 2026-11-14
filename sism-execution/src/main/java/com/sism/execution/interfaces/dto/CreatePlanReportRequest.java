package com.sism.execution.interfaces.dto;

import com.sism.execution.domain.report.ReportOrgType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * CreatePlanReportRequest - 创建计划报告请求DTO
 * 用于接收创建月度报告（草稿）的请求参数
 */
@Data
public class CreatePlanReportRequest {

    @NotBlank(message = "报告月份不能为空")
    private String reportMonth;

    @NotNull(message = "报告组织ID不能为空")
    private Long reportOrgId;

    @NotNull(message = "报告组织类型不能为空")
    private ReportOrgType reportOrgType;

    @NotNull(message = "计划ID不能为空")
    private Long planId;

    /**
     * 2026-11-13（学院上报按归口部门分批提交）：可选归口职能部门。
     * 前端选中部门后开草稿时传入，用于「同月同组织多部门」草稿隔离；
     * 不传=旧行为。权威归口仍以提交时反查落列为准。
     */
    private Long functionalOrgId;

    private Long createdBy;
}
