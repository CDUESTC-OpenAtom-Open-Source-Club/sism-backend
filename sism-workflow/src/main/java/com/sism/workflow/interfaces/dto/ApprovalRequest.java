package com.sism.workflow.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

/**
 * 审批请求 DTO
 */
@Data
public class ApprovalRequest {

    @NotBlank(message = "Comment is required")
    private String comment; // 批注

    /**
     * 本节点鉴定进度等级（P1 上报链）：AHEAD/NORMAL/DELAYED。
     * 中间节点仅留痕（audit_step_instance.appraisal_level），
     * 终审节点由 B9 逻辑投影到业务明细行；可空（通过/驳回不一定带等级）。
     */
    @Schema(description = "鉴定进度等级：AHEAD=超前 / NORMAL=正常 / DELAYED=延期，可空", example = "NORMAL")
    @Pattern(regexp = "^(?i)(AHEAD|NORMAL|DELAYED)$", message = "鉴定进度等级必须是 AHEAD/NORMAL/DELAYED")
    private String appraisalLevel;

    private Map<String, Object> variables;
}
