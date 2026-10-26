package com.sism.workflow.application.query;

import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.FlowDefinitionRepository;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.interfaces.dto.WorkflowDefinitionResponse;
import com.sism.workflow.interfaces.dto.WorkflowHistoryResponse;
import com.sism.workflow.interfaces.dto.WorkflowInstanceResponse;
import com.sism.workflow.interfaces.dto.WorkflowTaskResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WorkflowReadModelMapper {

    private final FlowDefinitionRepository flowDefinitionRepository;
    private final List<WorkflowBusinessContextPort> workflowBusinessContextPorts;

    public WorkflowReadModelMapper(
            FlowDefinitionRepository flowDefinitionRepository,
            List<WorkflowBusinessContextPort> workflowBusinessContextPorts) {
        this.flowDefinitionRepository = flowDefinitionRepository;
        this.workflowBusinessContextPorts = workflowBusinessContextPorts;
    }

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
        // 2026-10-26 修复：补 flowCode / flowName——前端审批中心按 flowCode 区分
        // 「计划下发 / 上报审批 / 异动审批」三类实例，缺 flowCode 会导致上报待办被整体过滤掉
        // （学院审核人看不到本学院上报的审批卡）。
        AuditFlowDef flowDef = instance.getFlowDefId() != null
                ? flowDefinitionRepository.findById(instance.getFlowDefId()).orElse(null)
                : null;
        return WorkflowInstanceResponse.builder()
                .instanceId(instance.getId() != null ? instance.getId().toString() : null)
                .definitionId(instance.getFlowDefId() != null ? instance.getFlowDefId().toString() : null)
                .flowCode(flowDef != null ? flowDef.getFlowCode() : null)
                .flowName(flowDef != null ? flowDef.getFlowName() : null)
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

    /**
     * 2026-10-26 修复：补齐实例的组织上下文（sourceOrgId / targetOrgId / planId）——
     * 前端审批中心按「审批人所属组织 / 业务来源组织」判断卡片与当前部门是否相关，
     * 缺这两个字段会导致上报待办被整体过滤（学院审核人看不到本学院上报的审批卡）。
     */
    public void enrichInstanceOrgContext(WorkflowInstanceResponse response, AuditInstance instance) {
        if (response == null || instance == null) {
            return;
        }
        response.setSourceOrgId(instance.getRequesterOrgId());
        response.setStarterId(instance.getRequesterId());
        for (WorkflowBusinessContextPort contextPort : workflowBusinessContextPorts) {
            java.util.Optional<WorkflowBusinessContextPort.BusinessSummary> summary =
                    contextPort.getBusinessSummary(instance.getEntityType(), instance.getEntityId());
            if (summary.isPresent()) {
                WorkflowBusinessContextPort.BusinessSummary value = summary.get();
                response.setPlanId(value.planId());
                response.setPlanName(value.planName());
                response.setSourceOrgId(value.sourceOrgId() != null ? value.sourceOrgId() : instance.getRequesterOrgId());
                response.setSourceOrgName(value.sourceOrgName());
                response.setTargetOrgId(value.targetOrgId());
                response.setTargetOrgName(value.targetOrgName());
                return;
            }
        }
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
