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
    private String currentStepName;
    private Long assigneeId;
    private String assigneeName;
    private Long approverOrgId;
    private String approverOrgName;
    private Integer stepNo;
    private String stepType;
    private String comment;
    /** 本节点鉴定进度等级 AHEAD/NORMAL/DELAYED（P1 上报链改造） */
    private String appraisalLevel;
    private LocalDateTime approvedAt;
    private LocalDateTime createdTime;
    private LocalDateTime startedAt;
    private LocalDateTime dueDate;
    private String claimUrl; // 前端可以直接打开的链接
    private Map<String, Object> variables;
}
