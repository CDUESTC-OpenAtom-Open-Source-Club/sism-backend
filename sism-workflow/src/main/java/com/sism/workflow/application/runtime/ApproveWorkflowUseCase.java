package com.sism.workflow.application.runtime;

import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.application.support.WorkflowEventDispatcher;
import com.sism.workflow.application.WorkflowBusinessStatusSyncService;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.definition.FlowDefinitionRepository;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApproveWorkflowUseCase {

    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final WorkflowBusinessStatusSyncService workflowBusinessStatusSyncService;
    private final FlowDefinitionRepository flowDefinitionRepository;
    private final ApproverResolver approverResolver;

    @Transactional
    public AuditInstance approve(AuditInstance instance, Long userId, String comment) {
        return approve(instance, userId, comment, null);
    }

    /**
     * 审批通过；appraisalLevel 非空时写入当前节点的鉴定进度等级（P1 上报链改造）。
     */
    @Transactional
    public AuditInstance approve(AuditInstance instance, Long userId, String comment, String appraisalLevel) {
        instance.approve(userId, comment, appraisalLevel);

        // 如果还在审批中，尝试创建下一个步骤
        if (AuditInstance.STATUS_PENDING.equals(instance.getStatus())) {
            boolean hasNextStep = createNextStep(instance);
            // 如果没有下一个步骤，标记为已完成
            if (!hasNextStep) {
                instance.setStatus(AuditInstance.STATUS_APPROVED);
                instance.setCompletedAt(java.time.LocalDateTime.now());
            }
        }

        AuditInstance saved = auditInstanceRepository.save(instance);
        workflowBusinessStatusSyncService.syncAfterWorkflowChanged(saved);
        workflowEventDispatcher.publish(saved);
        return saved;
    }

    private boolean createNextStep(AuditInstance instance) {
        AuditFlowDef flowDef = flowDefinitionRepository.findById(instance.getFlowDefId()).orElse(null);
        if (flowDef == null || flowDef.getSteps() == null || flowDef.getSteps().isEmpty()) {
            return false;
        }

        List<AuditStepDef> orderedSteps = flowDef.getSteps().stream()
                .sorted(Comparator.comparing(step -> step.getStepOrder() == null ? Integer.MAX_VALUE : step.getStepOrder()))
                .toList();

        AuditStepInstance latestHandledStep = instance.getStepInstances().stream()
                .filter(step -> AuditInstance.STEP_STATUS_APPROVED.equals(step.getStatus()))
                .max(Comparator.comparing(step -> step.getStepNo() == null ? 0 : step.getStepNo()))
                .orElse(null);
        if (latestHandledStep == null) {
            return false;
        }

        int currentStepIndex = findStepIndexByDefinitionId(orderedSteps, latestHandledStep.getStepDefId());
        if (currentStepIndex < 0 || currentStepIndex + 1 >= orderedSteps.size()) {
            return false;
        }

        AuditStepDef nextStepDef = orderedSteps.get(currentStepIndex + 1);
        AuditStepInstance nextStep = new AuditStepInstance();
        nextStep.setStepNo(instance.nextStepInstanceNo());
        nextStep.setStepDefId(nextStepDef.getId());
        nextStep.setStepName(nextStepDef.getStepName());

        Long contextOrgId = getCurrentApproverOrgId(instance);
        nextStep.setApproverId(approverResolver.resolveAssignedApproverId(
                nextStepDef,
                instance.getRequesterId(),
                contextOrgId,
                instance
        ));
        nextStep.setApproverOrgId(approverResolver.resolveApproverOrgId(nextStepDef, contextOrgId, instance));
        nextStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(nextStep);
        return true;
    }

    private Long getCurrentApproverOrgId(AuditInstance instance) {
        if (instance.getStepInstances() == null || instance.getStepInstances().isEmpty()) {
            return instance.getRequesterOrgId();
        }

        // 获取最后一个已审批步骤的审批人组织
        return instance.getStepInstances().stream()
                .filter(step -> AuditInstance.STEP_STATUS_APPROVED.equals(step.getStatus()))
                .max(Comparator.comparing(step -> step.getStepNo() == null ? 0 : step.getStepNo()))
                .map(step -> step.getApproverOrgId())
                .orElse(instance.getRequesterOrgId());
    }

    private int findStepIndexByDefinitionId(List<AuditStepDef> orderedSteps, Long stepDefId) {
        if (stepDefId == null) {
            return -1;
        }
        for (int i = 0; i < orderedSteps.size(); i++) {
            AuditStepDef step = orderedSteps.get(i);
            if (step.getId() != null && step.getId().equals(stepDefId)) {
                return i;
            }
        }
        return -1;
    }
}
