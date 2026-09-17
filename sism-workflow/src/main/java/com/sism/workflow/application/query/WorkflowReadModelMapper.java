package com.sism.workflow.application.query;

import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.interfaces.dto.WorkflowDefinitionResponse;
import com.sism.workflow.interfaces.dto.WorkflowHistoryResponse;
import com.sism.workflow.interfaces.dto.WorkflowInstanceResponse;
import com.sism.workflow.interfaces.dto.WorkflowTaskResponse;
import org.springframework.stereotype.Component;

@Component
public class WorkflowReadModelMapper {

    public WorkflowDefinitionResponse toDefinitionResponse(AuditFlowDef flowDef) {
        return WorkflowDefinitionResponse.builder()
                .definitionId(flowDef.getId().toString())
                .definitionCode(flowDef.getFlowCode())
                .definitionName(flowDef.getFlowName())
                .description(flowDef.getDescription())
                .category(flowDef.getEntityType())
                .version(flowDef.getVersion() != null ? flowDef.getVersion().toString() : "1")
                .isActive(flowDef.getIsActive() != null ? flowDef.getIsActive() : false)
                .createTime(flowDef.getCreatedAt())
                .build();
    }

    public WorkflowInstanceResponse toInstanceResponse(AuditInstance instance) {
        AuditStepInstance currentStep = instance.resolveCurrentDisplayStep().orElse(null);
        return WorkflowInstanceResponse.builder()
                .instanceId(instance.getId() != null ? instance.getId().toString() : null)
                .definitionId(instance.getFlowDefId() != null ? instance.getFlowDefId().toString() : null)
                .status(toExternalInstanceStatus(instance.getStatus()))
                .entityType(instance.getEntityType())
                .entityId(instance.getEntityId())
                .businessEntityId(instance.getEntityId())
                .starterId(instance.getRequesterId())
                .startTime(instance.getStartedAt())
                .endTime(instance.getCompletedAt())
                .currentTaskId(currentStep != null && currentStep.getId() != null ? currentStep.getId().toString() : null)
                .currentStepName(currentStep != null ? currentStep.getStepName() : null)
                .currentApproverId(currentStep != null ? currentStep.getApproverId() : null)
                .currentApproverName(null)
                .canWithdraw(instance.canRequesterWithdraw())
                .build();
    }

    public WorkflowTaskResponse toTaskResponse(AuditStepInstance step) {
        return WorkflowTaskResponse.builder()
                .taskId(step.getId() != null ? step.getId().toString() : null)
                .taskName(step.getStepName())
                .taskKey(step.getStepDefId() != null ? "step_" + step.getStepDefId() : "step_" + step.getStepNo())
                .status(convertStepStatus(step.getStatus()))
                .assigneeId(step.getApproverId())
                .assigneeName(null)
                .approverOrgId(step.getApproverOrgId())
                .stepNo(step.getStepNo())
                .comment(step.getComment())
                .appraisalLevel(step.getAppraisalLevel())
                .approvedAt(step.getApprovedAt())
                .createdTime(step.getCreatedAt())
                .build();
    }

    public WorkflowTaskResponse toPendingTaskResponse(AuditInstance instance, AuditStepInstance step) {
        return WorkflowTaskResponse.builder()
                .taskId(step.getId() != null ? step.getId().toString() : instance.getId().toString())
                .instanceId(instance.getId() != null ? instance.getId().toString() : null)
                .taskName(step.getStepName() != null ? step.getStepName() : buildInstanceLabel(instance))
                .taskKey(step.getStepDefId() != null ? "step_" + step.getStepDefId() : "step_" + step.getStepNo())
                .status("PENDING")
                .entityType(instance.getEntityType())
                .entityId(instance.getEntityId())
                .assigneeId(step.getApproverId())
                .assigneeName(null)
                .approverOrgId(step.getApproverOrgId())
                .stepNo(step.getStepNo())
                .createdTime(step.getCreatedAt() != null ? step.getCreatedAt() : instance.getStartedAt())
                .startedAt(instance.getStartedAt())
                .build();
    }

    public WorkflowHistoryResponse toHistoryResponse(AuditInstance instance, AuditStepInstance step, String action) {
        return WorkflowHistoryResponse.builder()
                .historyId(instance.getId() + "_step_" + step.getId())
                .taskId(instance.getId().toString())
                .taskName(step.getStepName())
                .operatorId(step.getApproverId())
                .operatorName(null)
                .action(action)
                .comment(step.getComment())
                .appraisalLevel(step.getAppraisalLevel())
                .operateTime(step.getApprovedAt() != null ? step.getApprovedAt() : step.getCreatedAt())
                .build();
    }

    public String buildInstanceLabel(AuditInstance instance) {
        if (instance == null) {
            return "Workflow";
        }
        return instance.getEntityType() + "#" + instance.getEntityId();
    }

    private String convertStepStatus(String stepStatus) {
        if (stepStatus == null) {
            return "PENDING";
        }
        return switch (stepStatus.toUpperCase()) {
            case "APPROVED" -> "COMPLETED";
            case "REJECTED" -> "REJECTED";
            case "WITHDRAWN" -> "WITHDRAWN";
            default -> "PENDING";
        };
    }

    private String toExternalInstanceStatus(String status) {
        return status;
    }
}
