package com.sism.workflow.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作流任务响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTaskResponse {

    private String taskId;
    private String instanceId;
    private String taskName;
    private String taskKey;
    private String status; // PENDING, COMPLETED, REJECTED
    private String entityType;
    private Long entityId;
    private Long planId;
    private String planName;
    private String flowCode;
    private String flowName;
    private Long sourceOrgId;
    private String sourceOrgName;
    private Long targetOrgId;
    private String targetOrgName;
    /**
     * 提交方（发起人所在）组织名，取 instance.requesterOrgId 对应组织。
     * 上报链（PLAN_REPORT）= 填报组织；下发/异动链 = 原下发方（与 sourceOrgName 相同）。
     * 2026-10-30 修复：上报链 sourceOrgName 是下发方向（plan.createdByOrgId），
     * 消息中心待办卡片的"提交人"应优先取本字段。
     */
    private String requesterOrgName;
    private String currentStepName;
    private Long assigneeId;
    private String assigneeName;
    private Long approverOrgId;
    private String approverOrgName;
    private Integer stepNo;
    private String stepType;
    private String comment;
    /** 本节点审核进度 AHEAD/NORMAL/DELAYED（P1 上报链改造） */
    private String appraisalLevel;
    private LocalDateTime approvedAt;
    private LocalDateTime createdTime;
    private LocalDateTime startedAt;
    private LocalDateTime dueDate;
    private String claimUrl; // 前端可以直接打开的链接
    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
     * 异动批次号（仅 PLAN_MUTATION_STRATEGY 流程有值），消息中心按它把
     * 同一次「发起异动」的多个审批实例聚合成 1 条待办消息。
     */
    private String batchId;
    private Map<String, Object> variables;
}
