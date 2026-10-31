package com.sism.strategy.infrastructure;

import com.sism.shared.domain.notification.DataChangeBroadcaster;
import com.sism.shared.domain.workflow.WorkflowBusinessStatusPort;
import com.sism.strategy.application.PlanApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StrategyWorkflowBusinessStatusAdapter implements WorkflowBusinessStatusPort {

    private static final String PLAN_ENTITY_TYPE = "PLAN";

    private final PlanApplicationService planApplicationService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    /** 2026-10-24 跨窗口数据同步：指标/任务随审批链发生任何变化即广播，其他窗口局部刷新。 */
    private final DataChangeBroadcaster dataChangeBroadcaster;

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
            // 2026-10-24：异动发起（IN_REVIEW 起始同步）到终态（落值/归档/解锁）
            // 都会改变其他窗口看到的数据，统一广播（fail-safe，在事务提交前的内存操作）
            dataChangeBroadcaster.publishDataChanged("INDICATOR", entityId);
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
                    // 2026-10-23 用户定案（状态更新与上报流程对齐）：异动发起不再原地落库，
                    // 审批通过此刻才把快照 after 值写入业务表；新增条目 after 与表值一致为幂等。
                    applyApprovedMutationValues("INDICATOR", entityId);
                    releaseMutationAndSyncPlanStatus(entityId);
                } else if ("CANCELLED".equalsIgnoreCase(status) && "CREATE".equals(mutationType)) {
                    // 2026-10-31 用户定案（取消异动软删除新增行）：含新增行的批次被「取消异动」
                    // 终结后，新增行已失效、不应继续留在表格里；但审批历史要能展示当时新增的
                    // 内容——数据走软删除（与审批拒绝归档同一列同一口径），界面看不到、
                    // audit_log 快照不删故审批历史看得到。CANCELLED+MODIFY / CANCELLED+DELETE
                    // 不动（维持现状，走下方解锁分支）。
                    jdbcTemplate.update(
                            """
                            UPDATE public.indicator
                            SET is_deleted = true, mutation_status = NULL, mutation_started_at = NULL,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """,
                            entityId);
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

        // 2026-10-21：战略任务改名异动同口径解锁。
        // 2026-10-23：与指标一致——审批通过此刻才把快照里的新任务名落库。
        if ("TASK".equalsIgnoreCase(entityType)) {
            dataChangeBroadcaster.publishDataChanged("TASK", entityId);
            if (!"IN_REVIEW".equalsIgnoreCase(status)) {
                if ("APPROVED".equalsIgnoreCase(status)) {
                    applyApprovedMutationValues("TASK", entityId);
                }
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
     * 2026-10-23：异动审批通过后按快照落业务值。
     *
     * 快照定位：audit_log.batch_id = 异动实例.batch_id 且该实例已 APPROVED（限
     * PLAN_MUTATION_STRATEGY 链，避免其他 INDICATOR/TASK 流程的通过事件误触发落库），
     * 取最新一条。不依赖调用方传实例 ID——终审通过时若实例已无「退回填报人」步骤，
     * 同步方传的 workflowInstanceId 为 null，不能作为定位依据（线上实测踩坑：
     * 重提复用实例后退回步已转 APPROVED，靠 ID 判空会整段跳过落库）。
     * 仅应用 changed_fields 中出现的业务字段；__delete__ 占位与 (新增)/(删除) 标记不参与。
     */
    private void applyApprovedMutationValues(String entityType, Long entityId) {
        String changedFieldsJson = jdbcTemplate.query(
                """
                SELECT al.changed_fields::text
                FROM public.audit_log al
                JOIN public.audit_instance ai ON ai.batch_id = al.batch_id
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                WHERE al.entity_type = ?
                  AND al.entity_id = ?
                  AND ai.status = 'APPROVED'
                ORDER BY al.log_id DESC
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                entityType, entityId);
        if (changedFieldsJson == null || changedFieldsJson.isBlank()) {
            return;
        }
        Map<String, Object> changedFields = parseJsonMap(changedFieldsJson);
        if (changedFields == null || changedFields.isEmpty()) {
            return;
        }

        if ("TASK".equalsIgnoreCase(entityType)) {
            String afterName = extractAfterValue(changedFields.get("name"));
            if (afterName != null && !afterName.isBlank()) {
                jdbcTemplate.update(
                        "UPDATE public.sys_task SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE task_id = ?",
                        afterName, entityId);
            }
            return;
        }

        if (!"INDICATOR".equalsIgnoreCase(entityType)) {
            return;
        }
        String afterDesc = extractAfterValue(changedFields.get("indicator_desc"));
        BigDecimal afterWeight = extractAfterDecimal(changedFields.get("weight_percent"));
        String afterRemark = extractAfterValue(changedFields.get("remark"));
        if (afterDesc == null && afterWeight == null && afterRemark == null) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET indicator_desc = COALESCE(?, indicator_desc),
                    weight_percent = COALESCE(?, weight_percent),
                    remark = COALESCE(?, remark),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                afterDesc, afterWeight, afterRemark, entityId);
    }

    /** changed_fields 形如 {"field": {"before": x, "after": y}}，取 after 值的字符串形态。 */
    private String extractAfterValue(Object delta) {
        if (!(delta instanceof Map<?, ?> map)) {
            return null;
        }
        Object after = map.get("after");
        if (after == null) {
            return null;
        }
        String text = String.valueOf(after).trim();
        // 新增/删除类占位快照不落业务值（新增行创建时已带终值，删除由归档分支处理）
        if ("(新增)".equals(text) || "(删除)".equals(text)) {
            return null;
        }
        return text;
    }

    private BigDecimal extractAfterDecimal(Object delta) {
        String after = extractAfterValue(delta);
        if (after == null || after.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(after);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> parseJsonMap(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
        } catch (Exception ex) {
            return null;
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
