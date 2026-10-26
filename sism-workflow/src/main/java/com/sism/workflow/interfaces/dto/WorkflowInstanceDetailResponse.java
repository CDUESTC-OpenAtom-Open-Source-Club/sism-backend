package com.sism.workflow.interfaces.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 工作流实例详情响应 DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowInstanceDetailResponse extends WorkflowInstanceResponse {

    private List<WorkflowTaskResponse> tasks;
    private List<WorkflowHistoryResponse> history;

    public WorkflowInstanceDetailResponse() {
        super();
    }

    public WorkflowInstanceDetailResponse(
            String instanceId, String definitionId, String status, String entityType, Long entityId,
            Long businessEntityId, String flowCode, String flowName, Long starterId, String starterName,
            Long planId, String planName, Long sourceOrgId, String sourceOrgName,
            Long targetOrgId, String targetOrgName, java.time.LocalDateTime startTime,
            java.time.LocalDateTime endTime,
            List<WorkflowTaskResponse> tasks, List<WorkflowHistoryResponse> history) {
        super(instanceId, definitionId, status, entityType, entityId, businessEntityId, flowCode, flowName,
                starterId, starterName, planId, planName, sourceOrgId, sourceOrgName, targetOrgId,
                targetOrgName, startTime, endTime, null, null, null, null, null, null);
        this.tasks = tasks;
        this.history = history;
    }
}
