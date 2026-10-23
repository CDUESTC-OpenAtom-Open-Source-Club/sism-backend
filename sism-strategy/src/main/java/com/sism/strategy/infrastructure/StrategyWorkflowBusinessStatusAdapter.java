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
                // 2026-10-22：终态收尾统一为三条规则（用户定案：按整个 Plan 的状态记录）——
                // ① 审批通过（除删除外）：解锁，并把指标状态对齐到所属计划；
                //    已下发计划上的指标（含异动期间新增的、历史遗留的 DRAFT）即视为已下发，
                //    不再按异动类型区分——异动以「修改」类型走完的新增指标同样要转正。
                // ② 删除通过：此时才真正归档。
                // ③ 新增异动被最终拒绝（非退回重改）：归档，避免 DRAFT 残留污染权重合计。
                //    驳回退回上一节点（含退回填报人）与发起人撤回：保留现场，仅解锁，
                //    等发起人修改后重新发起——对话承诺的「驳回后可修改后重新发起」。
                String mutationType = resolveLatestMutationType(entityId);
                boolean terminalApproved = "APPROVED".equalsIgnoreCase(status);
                boolean returnedToSubmitter = workflowInstanceId != null;
                if (terminalApproved && "DELETE".equals(mutationType)) {
                    jdbcTemplate.update(
                            """
                            UPDATE public.indicator
                            SET is_deleted = true, mutation_status = NULL, mutation_started_at = NULL,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """,
                            entityId);
                } else if (terminalApproved) {
                    releaseMutationAndSyncPlanStatus(entityId);
                } else if (!returnedToSubmitter && "CREATE".equals(mutationType)) {
                    jdbcTemplate.update(
                            """
                            UPDATE public.indicator
                            SET is_deleted = true, mutation_status = NULL, mutation_started_at = NULL,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """,
                            entityId);
                } else {
                    jdbcTemplate.update(
                            "UPDATE public.indicator SET mutation_status = NULL, mutation_started_at = NULL WHERE id = ?",
                            entityId);
                }
            }
            return;
        }

        // 2026-10-21：战略任务改名异动同口径解锁
        if ("TASK".equalsIgnoreCase(entityType)) {
            if (!"IN_REVIEW".equalsIgnoreCase(status)) {
                jdbcTemplate.update(
                        "UPDATE public.sys_task SET mutation_status = NULL, mutation_started_at = NULL WHERE task_id = ?",
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

    /**
     * 取该指标最近一次异动的类型：DELETE 记为 "__delete__"，CREATE 记为 "indicator_desc: (新增)"，
     * 其余按 MODIFY 处理。快照由 IndicatorMutationService 写入 audit_log。
     */
    private String resolveLatestMutationType(Long indicatorId) {
        return jdbcTemplate.query(
                """
                SELECT changed_fields::text
                FROM public.audit_log
                WHERE entity_type = 'INDICATOR' AND entity_id = ?
                ORDER BY created_at DESC, log_id DESC
                LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return "MODIFY";
                    }
                    String raw = rs.getString(1);
                    if (raw == null) {
                        return "MODIFY";
                    }
                    if (raw.contains("__delete__")) {
                        return "DELETE";
                    }
                    if (raw.contains("(新增)")) {
                        return "CREATE";
                    }
                    return "MODIFY";
                },
                indicatorId);
    }

    /**
     * 异动审批通过后的收尾：解锁，并把指标状态对齐到所属计划的状态。
     *
     * 2026-10-22 用户定案：已下发计划上的指标，异动审批通过后即视为已下发——
     * 不再按异动类型区分「新增/修改」。历史遗留的 DRAFT 行（如导入或旧版本残留）
     * 也会在同计划下一次异动通过时被顺带扶正，避免永远卡在「未下发」。
     */
    private void releaseMutationAndSyncPlanStatus(Long indicatorId) {
        String planStatus = jdbcTemplate.query(
                """
                SELECT p.status
                FROM public.indicator i
                JOIN public.sys_task t ON t.task_id = i.task_id
                JOIN public.plan p ON p.id = t.plan_id
                WHERE i.id = ?
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                indicatorId);
        if (planStatus != null && "DISTRIBUTED".equalsIgnoreCase(planStatus)) {
            jdbcTemplate.update(
                    """
                    UPDATE public.indicator
                    SET status = 'DISTRIBUTED', mutation_status = NULL, mutation_started_at = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status <> 'DISTRIBUTED'
                    """,
                    indicatorId);
            // 状态推进语句带 status <> 'DISTRIBUTED' 条件，可能不命中；
            // 解锁必须无条件执行
            jdbcTemplate.update(
                    "UPDATE public.indicator SET mutation_status = NULL, mutation_started_at = NULL WHERE id = ?",
                    indicatorId);
            return;
        }
        jdbcTemplate.update(
                "UPDATE public.indicator SET mutation_status = NULL, mutation_started_at = NULL WHERE id = ?",
                indicatorId);
    }
}
