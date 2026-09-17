package com.sism.workflow.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Unified task decision request DTO.
 * Uses the current authenticated user as operator.
 */
@Data
public class WorkflowTaskDecisionRequest {

    @NotNull(message = "Decision result is required")
    private Boolean approved;

    private String comment;

    /**
     * 鉴定进度等级（P1 上报链改造）：审批通过时可选填写，
     * 取值 AHEAD / NORMAL / DELAYED（服务端用 ProgressLevel 归一校验，
     * 同时兼容旧预警档位码并归并为 DELAYED）。
     */
    private String appraisalLevel;
}
