package com.sism.workflow.application.runtime;

import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.application.support.WorkflowEventDispatcher;
import com.sism.workflow.application.WorkflowBusinessStatusSyncService;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RejectWorkflowUseCase {

    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final WorkflowBusinessStatusSyncService workflowBusinessStatusSyncService;
    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final ApproverResolver approverResolver;
    @Transactional
    public AuditInstance reject(AuditInstance instance, Long userId, String comment) {
        log.info("Rejecting workflow instance {}, before reject step count: {}", instance.getId(), instance.getStepInstances().size());
        instance.reject(userId, comment);
        createReturnedStep(instance);
        log.info("Rejected workflow instance {}, after return step creation count: {}, current pending step: {}",
                instance.getId(),
                instance.getStepInstances().size(),
                instance.resolveCurrentPendingStep().map(AuditStepInstance::getId).orElse(null));
        AuditInstance saved = auditInstanceRepository.save(instance);
        log.info("Saved rejected workflow instance {}, persisted step count: {}, current pending step: {}",
                saved.getId(),
                saved.getStepInstances().size(),
                saved.resolveCurrentPendingStep().map(AuditStepInstance::getId).orElse(null));
        workflowBusinessStatusSyncService.syncAfterWorkflowChanged(saved);
        workflowEventDispatcher.publish(saved);
        return saved;
    }

    private void createReturnedStep(AuditInstance instance) {
        AuditStepInstance rejectedStep = instance.getStepInstances().stream()
                .filter(step -> AuditInstance.STEP_STATUS_REJECTED.equals(step.getStatus()))
                .max(Comparator.comparing(step -> step.getStepNo() == null ? 0 : step.getStepNo()))
                .orElse(null);
        if (rejectedStep == null) {
            log.warn("Reject workflow instance {} could not find rejected step", instance.getId());
            instance.setStatus(AuditInstance.STATUS_REJECTED);
            return;
        }

        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefById(instance.getFlowDefId());
        if (flowDef == null || flowDef.getSteps() == null || flowDef.getSteps().isEmpty()) {
            log.warn("Reject workflow instance {} has no flow definition steps", instance.getId());
            instance.setStatus(AuditInstance.STATUS_REJECTED);
            instance.setCompletedAt(java.time.LocalDateTime.now());
            return;
        }

        List<AuditStepDef> orderedSteps = flowDef.getSteps().stream()
                .sorted(Comparator.comparing(step -> step.getStepOrder() == null ? Integer.MAX_VALUE : step.getStepOrder()))
                .toList();

        int rejectedIndex = findStepIndexByDefinitionId(orderedSteps, rejectedStep.getStepDefId());
        AuditStepDef rejectedStepDef = rejectedIndex >= 0 ? orderedSteps.get(rejectedIndex) : null;
        if (rejectedIndex <= 0) {
            if (isTerminalStep(rejectedStepDef)) {
                log.info("Reject workflow instance {} stopped at terminal step, marking as rejected", instance.getId());
                instance.setStatus(AuditInstance.STATUS_REJECTED);
                instance.setCompletedAt(java.time.LocalDateTime.now());
                return;
            }

            log.info("Reject workflow instance {} has no earlier step but current step is not terminal, keeping in review", instance.getId());
            instance.setStatus(AuditInstance.STATUS_PENDING);
            instance.setCompletedAt(null);
            return;
        }

        AuditStepDef returnedStepDef = orderedSteps.get(rejectedIndex - 1);
        AuditStepInstance returnedStep = new AuditStepInstance();
        returnedStep.setStepNo(instance.nextStepInstanceNo());
        returnedStep.setStepDefId(returnedStepDef.getId());
        returnedStep.setStepName(returnedStepDef.getStepName());

        // 2026-11-12（HIGH①，实例 #5 实测）：回退节点的席位一律按该节点自身的解析规则重解，
        // context 一律用实例的请求方组织——与派单 StepInstanceFactory、正常推进共用
        // ApproverResolver.resolveStepScopeOrgId 同一套口径（学院节点→requesterOrg（学院 org）；
        // 归口节点→plan.functional_org_id；战略部终审→strategy org；学院下发链→strategy org）。
        // 此前 context 取「最后一个已批步骤的 approverOrgId」：学院上报链（工学院式五节点）
        // 从终审逐级驳回回退到学院审核人节点时，会把归口部门 org（44）错继承给学院席位
        // （应为学院 org 57），学院审核人收不到待办、链卡死。同部门链内回退不受影响
        // （last approved org == requesterOrg，部门上报实例 #6 org=37 口径不变）。
        Long contextOrgId = instance.getRequesterOrgId();
        Long approverId = approverResolver.resolveAssignedApproverId(
                returnedStepDef,
                instance.getRequesterId(),
                contextOrgId,
                instance
        );
        returnedStep.setApproverId(approverId);
        returnedStep.setApproverOrgId(returnedStepDef.isSubmitStep()
                ? instance.getRequesterOrgId()
                : approverResolver.resolveApproverOrgId(returnedStepDef, contextOrgId, instance));
        returnedStep.setStatus(returnedStepDef.isSubmitStep()
                ? AuditInstance.STEP_STATUS_WITHDRAWN
                : AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(returnedStep);

        if (returnedStepDef.isSubmitStep()) {
            returnedStep.setComment("驳回后退回填报人重新提交");
            returnedStep.setApprovedAt(null);
        }

        log.info("Reject workflow instance {} appended returned step defId={}, stepNo={}, approverId={}",
                instance.getId(),
                returnedStep.getStepDefId(),
                returnedStep.getStepNo(),
                returnedStep.getApproverId());
        if (returnedStepDef.isSubmitStep()) {
            instance.setStatus(AuditInstance.STATUS_WITHDRAWN);
        } else {
            instance.setStatus(AuditInstance.STATUS_PENDING);
        }
        instance.setCompletedAt(null);
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

    private boolean isTerminalStep(AuditStepDef stepDef) {
        return stepDef != null && Boolean.TRUE.equals(stepDef.getIsTerminal());
    }

}
