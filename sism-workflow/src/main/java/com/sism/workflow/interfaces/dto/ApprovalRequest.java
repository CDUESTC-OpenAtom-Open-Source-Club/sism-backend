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

    /**
     * 2026-10-24 指标级鉴定：逐指标鉴定等级（指标ID → 超前完成/正常/延期）。
     * 2026-10-27 修复：controller 层此前缺失该字段，前端提交的逐指标等级
     * 在进入服务层前就被丢弃，导致「按指标逐条评定」从未真正生效。
     */
    private java.util.Map<Long, String> indicatorLevels;

    private Map<String, Object> variables;
}
