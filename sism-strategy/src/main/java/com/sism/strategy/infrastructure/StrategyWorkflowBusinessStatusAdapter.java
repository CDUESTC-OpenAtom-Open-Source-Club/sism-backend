package com.sism.strategy.infrastructure;

import com.sism.shared.domain.workflow.WorkflowBusinessStatusPort;
import com.sism.strategy.application.PlanApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StrategyWorkflowBusinessStatusAdapter implements WorkflowBusinessStatusPort {

    private static final String PLAN_ENTITY_TYPE = "PLAN";

    private final PlanApplicationService planApplicationService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Override
    public void syncBusinessStatus(String entityType, Long entityId, String status, Long operatorId, String comment, Long workflowInstanceId) {
        if (entityId == null || status == null) {
            return;
        }

        // P5 指标异动：实例到达任一非审批中状态（通过/驳回/撤回/退回发起人）即解除异动锁；
        // 审批中间步（IN_REVIEW，含驳回退回中间审批人）保持锁死。
        // 原先 endMutationIfTerminal 只挂在 approveTask/reassignTask 旧链路上，
        // instances/{id}/approve|reject 实际链路经此同步点，此处为统一解锁位置。
        if ("INDICATOR".equalsIgnoreCase(entityType)) {
            if (!"IN_REVIEW".equalsIgnoreCase(status)) {
                jdbcTemplate.update(
                        "UPDATE public.indicator SET mutation_status = NULL, mutation_started_at = NULL WHERE id = ?",
                        entityId);
            }
            return;
        }

        if (!PLAN_ENTITY_TYPE.equalsIgnoreCase(entityType)) {
            return;
        }

        if ("APPROVED".equalsIgnoreCase(status)) {
            planApplicationService.markWorkflowApproved(entityId);
            return;
        }
        if ("WITHDRAWN".equalsIgnoreCase(status)) {
            planApplicationService.markWorkflowWithdrawn(entityId);
            return;
        }
        if ("PENDING".equalsIgnoreCase(status)) {
            if (workflowInstanceId != null) {
                planApplicationService.markWorkflowPending(entityId);
            } else {
                planApplicationService.markWorkflowPending(entityId);
            }
            return;
        }
        if ("RETURNED".equalsIgnoreCase(status)) {
            planApplicationService.markWorkflowWithdrawn(entityId);
            return;
        }
        if ("REJECTED".equalsIgnoreCase(status)) {
            planApplicationService.markWorkflowRejected(
                    entityId,
                    comment == null || comment.isBlank() ? "Rejected" : comment
            );
        }
    }
}
