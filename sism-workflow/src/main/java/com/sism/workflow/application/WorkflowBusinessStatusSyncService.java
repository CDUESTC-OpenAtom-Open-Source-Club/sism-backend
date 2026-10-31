package com.sism.workflow.application;

import com.sism.shared.domain.workflow.WorkflowBusinessStatusPort;
import com.sism.workflow.domain.runtime.AuditInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowBusinessStatusSyncService {

    private final List<WorkflowBusinessStatusPort> statusPorts;

    public void syncAfterWorkflowChanged(AuditInstance instance) {
        if (instance == null || instance.getEntityId() == null) {
            return;
        }
        String comment = latestRejectedComment(instance);
        Long workflowInstanceId = hasReturnedSubmitStep(instance) ? instance.getId() : null;
        for (WorkflowBusinessStatusPort statusPort : statusPorts) {
            statusPort.syncBusinessStatus(
                    instance.getEntityType(),
                    instance.getEntityId(),
                    normalizeStatus(instance),
                    instance.getRequesterId(),
                    comment,
                    workflowInstanceId
            );
        }
    }

    private String normalizeStatus(AuditInstance instance) {
        if (AuditInstance.STATUS_PENDING.equals(instance.getStatus()) && hasReturnedSubmitStep(instance)) {
            return "RETURNED";
        }
        return instance.getStatus();
    }

    private String latestRejectedComment(AuditInstance instance) {
        return instance.getStepInstances().stream()
                .filter(step -> AuditInstance.STEP_STATUS_REJECTED.equals(step.getStatus()))
                .reduce((first, second) -> second)
                .map(step -> step.getComment() == null || step.getComment().isBlank() ? "审批驳回" : step.getComment())
                .orElse(null);
    }

    /**
     * 「退回提交步骤」识别（2026-10-30 二次定案：异动撤回零新增行后的双形态口径）：
     * 存在 WITHDRAWN 的提交类步骤即视为退回填报人——
     * 新形态：发起人撤回把最新提交类步骤原地置 WITHDRAWN（在审审批步也翻
     * WITHDRAWN，故不能再按「最新 WITHDRAWN 步骤是否提交类」判，否则新形态漏判）；
     * 旧形态：存量数据里用例层追加的退回提交行（WITHDRAWN、审批人=发起人）。
     * 识别三口径与 AuditInstance.isRequesterSubmitStep 一致：stepNo=1 /
     * 审批人=发起人 / 步骤名含「提交」。重报后提交步翻回 APPROVED、判定自然失效，
     * 上报 cancel 链形（submit 步 WITHDRAWN + completedAt 非空）行为不变。
     */
    private boolean hasReturnedSubmitStep(AuditInstance instance) {
        if (instance == null || instance.getStepInstances() == null || instance.getStepInstances().isEmpty()) {
            return false;
        }
        return instance.getStepInstances().stream()
                .filter(step -> AuditInstance.STEP_STATUS_WITHDRAWN.equals(step.getStatus()))
                .anyMatch(step -> {
                    if (Integer.valueOf(1).equals(step.getStepNo())) {
                        return true;
                    }
                    if (instance.getRequesterId() != null && instance.getRequesterId().equals(step.getApproverId())) {
                        return true;
                    }
                    String stepName = step.getStepName();
                    return stepName != null && stepName.contains("提交");
                });
    }
}
