package com.sism.workflow.application.support;

import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;

@Component
public class SubmissionStepAutoCompletePolicy {

    public void apply(AuditInstance instance, AuditStepDef firstStepDef, Long requesterId, String submitComment) {
        if (instance.getStepInstances() == null || instance.getStepInstances().isEmpty()) {
            return;
        }

        AuditStepInstance firstStep = instance.getStepInstances().stream()
                .sorted(Comparator.comparing(step -> step.getStepNo() == null ? Integer.MAX_VALUE : step.getStepNo()))
                .findFirst()
                .orElse(null);

        if (firstStep == null) {
            return;
        }

        if (!shouldAutoCompleteSubmissionStep(firstStep, firstStepDef, requesterId)) {
            return;
        }

        firstStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        firstStep.setApprovedAt(LocalDateTime.now());
        firstStep.setComment(resolveSubmitComment(submitComment));

        AuditStepInstance nextStep = instance.getStepInstances().stream()
                .filter(step -> !step.equals(firstStep))
                .filter(step -> AuditInstance.STEP_STATUS_WAITING.equals(step.getStatus()))
                .sorted(Comparator.comparing(step -> step.getStepNo() == null ? Integer.MAX_VALUE : step.getStepNo()))
                .findFirst()
                .orElse(null);

        if (nextStep != null) {
            nextStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        }
    }

    public void apply(AuditInstance instance, AuditStepDef firstStepDef, Long requesterId) {
        apply(instance, firstStepDef, requesterId, null);
    }

    public boolean shouldAutoCompleteSubmissionStep(AuditStepInstance firstStep, AuditStepDef firstStepDef, Long requesterId) {
        if (!AuditInstance.STEP_STATUS_PENDING.equals(firstStep.getStatus())) {
            return false;
        }

        if (firstStepDef == null || !firstStepDef.isSubmitStep()) {
            return false;
        }

        return requesterId != null && requesterId.equals(firstStep.getApproverId());
    }

    private String resolveSubmitComment(String submitComment) {
        if (submitComment != null && !submitComment.trim().isEmpty()) {
            return submitComment.trim();
        }
        return "系统自动完成提交流程节点";
    }
}
