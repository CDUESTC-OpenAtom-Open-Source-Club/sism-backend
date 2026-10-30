package com.sism.workflow.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

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
     * 审核进度（P1 上报链改造）：审批通过时可选填写，
     * 取值 AHEAD / NORMAL / DELAYED（服务端用 ProgressLevel 归一校验，
     * 同时兼容旧预警档位码并归并为 DELAYED）。
     *
     * 2026-10-24 用户定案（指标级鉴定）：整体等级已废弃，审批弹窗不再提供
     * 单一等级选择器；该字段仅为兼容保留，为空即不投影。
     */
    private String appraisalLevel;

    /**
     * 2026-10-24 用户定案：审核进度按指标逐条评定——
     * key=指标ID（plan_report_indicator.indicator_id），value=AHEAD/NORMAL/DELAYED。
     * 仅末级审批节点（上级部门鉴定环节）提交；实例到达终态 APPROVED 时按指标投影，
     * 与旧的整单 appraisalLevel 互斥时以本字段优先。
     */
    private Map<Long, String> indicatorLevels;
}
