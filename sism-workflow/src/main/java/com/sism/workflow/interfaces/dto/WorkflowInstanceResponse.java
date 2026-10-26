package com.sism.workflow.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工作流实例响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowInstanceResponse {

    private String instanceId;
    private String definitionId;
    private String status; // External states: IN_REVIEW, APPROVED, REJECTED
    private String entityType;
    private Long entityId;
    private Long businessEntityId;
    private String flowCode;
    private String flowName;
    private Long starterId;
    private String starterName;
    private Long planId;
    private String planName;
    private Long sourceOrgId;
    private String sourceOrgName;
    private Long targetOrgId;
    private String targetOrgName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String currentTaskId;
    private String currentStepName;
    private Long currentApproverId;
    /** 2026-10-26 修复：当前审批人所属组织——前端按它判断卡片与当前部门是否相关 */
    private Long approverOrgId;
    private String currentApproverName;
    private Boolean canWithdraw;
}
