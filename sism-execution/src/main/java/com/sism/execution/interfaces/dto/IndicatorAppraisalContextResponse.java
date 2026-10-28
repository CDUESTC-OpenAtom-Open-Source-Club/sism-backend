package com.sism.execution.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指标鉴定进度上下文（2026-10-28）。
 *
 * - reportStatus = IN_REVIEW 且 canAppraise = true 时，当前用户是该审批节点的负责人，
 *   列表页的行内下拉可编辑；
 * - 其他情况一律只读；appraisalLevel 是当前有效等级（上一节点已评定 / 已通过轮次）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorAppraisalContextResponse {

    private Long indicatorId;
    private Long reportId;
    private String reportMonth;
    private String reportStatus;
    private String appraisalLevel;
    private boolean canAppraise;
    private String currentStepName;
    /** 不可编辑时的原因提示（前端 tooltip 直接用） */
    private String lockHint;
}
