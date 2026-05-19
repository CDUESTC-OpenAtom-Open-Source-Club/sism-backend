package com.sism.workflow.application.support;

import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class StepInstanceFactory {

    private final ApproverResolver approverResolver;
    private final SubmissionStepAutoCompletePolicy submissionStepAutoCompletePolicy;

    public void initialize(AuditInstance instance,
                           AuditFlowDef flowDef,
                           Long requesterId,
                           Long requesterOrgId,
                           String submitComment) {
        if (instance.getStepInstances() != null && !instance.getStepInstances().isEmpty()) {
            return;
        }

        List<AuditStepDef> stepDefs = flowDef != null && flowDef.getSteps() != null ? flowDef.getSteps() : List.of();
        if (stepDefs.isEmpty()) {
            AuditStepInstance fallback = new AuditStepInstance();
            fallback.setStepNo(1);
            fallback.setStepName("默认审批");
            fallback.setStatus(AuditInstance.STEP_STATUS_PENDING);
            fallback.setApproverId(requesterId);
            fallback.setApproverOrgId(requesterOrgId);
            instance.addStepInstance(fallback);
            return;
        }

        List<AuditStepDef> orderedStepDefs = stepDefs.stream()
                .sorted(Comparator
                        .comparing((AuditStepDef step) -> step.getStepOrder() == null ? Integer.MAX_VALUE : step.getStepOrder())
                        .thenComparing(step -> step.getId() == null ? Long.MAX_VALUE : step.getId()))
                .toList();

        // 只创建第一个步骤
        AuditStepDef firstStepDef = orderedStepDefs.get(0);
        validateStepDefinition(firstStepDef);
        AuditStepInstance firstStep = new AuditStepInstance();
        firstStep.setStepNo(1);
        firstStep.setStepDefId(firstStepDef.getId());
        firstStep.setStepName(firstStepDef.getStepName() != null ? firstStepDef.getStepName() : "审批步骤1");
        Long resolvedApproverId = approverResolver.resolveAssignedApproverId(
                firstStepDef,
                requesterId,
                requesterOrgId,
                instance
        );
        firstStep.setApproverId(resolvedApproverId);
        firstStep.setApproverOrgId(firstStepDef.isSubmitStep()
                ? requesterOrgId
                : approverResolver.resolveApproverOrgId(firstStepDef, requesterOrgId, instance));
        firstStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(firstStep);

        submissionStepAutoCompletePolicy.apply(instance, orderedStepDefs.get(0), requesterId, submitComment);

        // 如果第一步被自动完成，创建第二个步骤
        if (AuditInstance.STEP_STATUS_APPROVED.equals(firstStep.getStatus()) && orderedStepDefs.size() > 1) {
            AuditStepDef secondStepDef = orderedStepDefs.get(1);
            validateStepDefinition(secondStepDef);
            AuditStepInstance secondStep = new AuditStepInstance();
            secondStep.setStepNo(2);
            secondStep.setStepDefId(secondStepDef.getId());
            secondStep.setStepName(secondStepDef.getStepName() != null ? secondStepDef.getStepName() : "审批步骤2");
            secondStep.setApproverId(approverResolver.resolveAssignedApproverId(
                    secondStepDef,
                    requesterId,
                    requesterOrgId,
                    instance
            ));
            secondStep.setApproverOrgId(approverResolver.resolveApproverOrgId(secondStepDef, requesterOrgId, instance));
            secondStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
            instance.addStepInstance(secondStep);
        }
    }

    public void initialize(AuditInstance instance,
                           AuditFlowDef flowDef,
                           Long requesterId,
                           Long requesterOrgId) {
        initialize(instance, flowDef, requesterId, requesterOrgId, null);
    }

    private void validateStepDefinition(AuditStepDef stepDef) {
        if (!stepDef.hasExplicitStepType()) {
            log.warn("Workflow step is missing explicit step_type, using compatibility fallback: stepName={}, inferredType={}",
                    stepDef.getStepName(), stepDef.resolveEffectiveStepType());
        }
        if (stepDef.isApprovalStep() && (stepDef.getRoleId() == null || stepDef.getRoleId() <= 0)) {
            throw new IllegalStateException("Workflow approval step is missing role assignment: " + stepDef.getStepName());
        }
    }
}
