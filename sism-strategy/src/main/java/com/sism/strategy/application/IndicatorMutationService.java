package com.sism.strategy.application;

import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.interfaces.dto.StartWorkflowRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5 指标异动（会议定案）：
 * - 原地修改（ID 不变），不复制副本
 * - 每次变更写 audit_log 快照（before_json/after_json/changed_fields）供「已更改 N 次」与历史版本
 * - 异动只能由战略发展部发起（分管校领导终审，PLAN_MUTATION_STRATEGY 三级链）
 * - 异动审批期间全面锁死该组织填报/提交（isOrgLockedByMutation）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorMutationService {

    private static final String MUTATION_STATUS_IN_MUTATION = "IN_MUTATION";
    private static final String INDICATOR_ENTITY_TYPE = "INDICATOR";
    private static final long STRATEGY_ORG_ID = 35L;
    private static final long ROLE_VICE_PRESIDENT_ID = 4L;

    /** A3 锁死主动通知：标题/文案为会议定案口径。 */
    private static final String MUTATION_LOCK_NOTICE_TITLE = "指标异动审批中";
    private static final String MUTATION_LOCK_NOTICE_CONTENT =
            "上级正在对该部门的指标进行异动审批，期间暂不能填报或提交，请稍后再试";
    private static final String MUTATION_LOCK_NOTICE_TYPE = "INDICATOR_MUTATION";

    private final JdbcTemplate jdbcTemplate;
    private final BusinessWorkflowApplicationService businessWorkflowApplicationService;
    private final com.sism.shared.domain.user.UserProvider userProvider;
    private final com.sism.strategy.infrastructure.StrategyOrgProperties strategyOrgProperties;

    public boolean canInitiate(Long operatorUserId) {
        if (operatorUserId == null || operatorUserId <= 0) {
            return false;
        }
        Long orgId = userProvider.getUserOrgId(operatorUserId).orElse(null);
        if (orgId != null && orgId == strategyOrgProperties.getStrategyOrgId()) {
            return true;
        }
        List<Long> roleIds = userProvider.getUserRoleIds(operatorUserId);
        return roleIds != null && roleIds.contains(ROLE_VICE_PRESIDENT_ID);
    }

    /** 发起异动：写快照 + 打异动标记 + 启动异动审批链。返回工作流实例 ID。 */
    @Transactional
    public String initiate(Long indicatorId,
                           Map<String, Object> changes,
                           Long operatorUserId) {
        if (indicatorId == null || indicatorId <= 0) {
            throw new IllegalArgumentException("指标 ID 不能为空");
        }
        if (!canInitiate(operatorUserId)) {
            throw new SecurityException("仅战略发展部可发起指标异动");
        }

        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT id, indicator_desc, weight_percent, remark, status, mutation_status
                FROM public.indicator WHERE id = ? AND COALESCE(is_deleted, false) = false
                """,
                indicatorId);
        String status = String.valueOf(current.get("status"));
        if (!"DISTRIBUTED".equalsIgnoreCase(status)) {
            throw new IllegalStateException("仅已下发（稳定态）的指标可发起异动");
        }
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该指标已有异动在审批中");
        }

        // 快照：before = 当前值，after = 应用变更后的值，changed_fields = 变更字段名
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("indicator_desc", current.get("indicator_desc"));
        before.put("weight_percent", current.get("weight_percent"));
        before.put("remark", current.get("remark"));

        Map<String, Object> after = new LinkedHashMap<>(before);
        Map<String, Object> changedFields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if ("indicator_desc".equals(field)) {
                after.put(field, value);
                changedFields.put(field, Map.of("before", before.get(field), "after", value));
            } else if ("weight_percent".equals(field) || "remark".equals(field)) {
                after.put(field, value);
                changedFields.put(field, Map.of("before", before.get(field), "after", value));
            }
        }
        if (changedFields.isEmpty()) {
            throw new IllegalArgumentException("异动内容为空：至少提供一个有效变更字段（indicator_desc/weight_percent/remark）");
        }

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId);

        // 原地应用变更（ID 不变）
        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET indicator_desc = COALESCE(?, indicator_desc),
                    weight_percent = COALESCE(?, weight_percent),
                    remark = COALESCE(?, remark),
                    mutation_status = ?,
                    mutation_started_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                after.get("indicator_desc"),
                toBigDecimal(after.get("weight_percent")),
                after.get("remark"),
                MUTATION_STATUS_IN_MUTATION,
                indicatorId
        );

        // 启动异动审批链（填报人修改 → 战略部负责人 → 分管校领导终审）
        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setWorkflowCode("PLAN_MUTATION_STRATEGY");
        request.setBusinessEntityId(indicatorId);
        request.setBusinessEntityType(INDICATOR_ENTITY_TYPE);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("indicatorId", indicatorId);
        variables.put("initiatedBy", operatorUserId);
        request.setVariables(variables);
        var response = businessWorkflowApplicationService.startWorkflow(
                request, operatorUserId, strategyOrgProperties.getStrategyOrgId());
        log.info("[IndicatorMutation] 异动已发起: indicatorId={}, instanceId={}",
                indicatorId, response.getInstanceId());

        // A3 锁死主动通知：异动发起成功后，给被锁组织用户写一条站内消息
        notifyLockedOrgUsers(indicatorId, operatorUserId);
        return response.getInstanceId();
    }

    /**
     * A3 锁死主动通知：异动发起成功后，给被锁组织（指标的 target_org_id）的全部在岗用户
     * 写一条站内消息（直接落 sys_user_notification，与 iam UserNotificationService 同表同结构）。
     * 尽力而为：查询/写入失败只记日志，不影响异动发起主流程。
     */
    private void notifyLockedOrgUsers(Long indicatorId, Long operatorUserId) {
        try {
            List<Long> targetOrgIds = jdbcTemplate.queryForList(
                    "SELECT target_org_id FROM public.indicator WHERE id = ?",
                    Long.class, indicatorId);
            Long targetOrgId = targetOrgIds == null || targetOrgIds.isEmpty() ? null : targetOrgIds.get(0);
            if (targetOrgId == null) {
                log.info("[IndicatorMutation] 指标无 target_org_id，跳过锁死通知: indicatorId={}", indicatorId);
                return;
            }

            List<Long> recipientIds = jdbcTemplate.query(
                    """
                    SELECT u.id
                    FROM public.sys_user u
                    WHERE u.org_id = ?
                      AND COALESCE(u.is_active, false) = true
                    ORDER BY u.id ASC
                    """,
                    (rs, rowNum) -> rs.getLong(1),
                    targetOrgId);
            if (recipientIds.isEmpty()) {
                log.info("[IndicatorMutation] 被锁组织无在岗用户，跳过锁死通知: indicatorId={}, orgId={}",
                        indicatorId, targetOrgId);
                return;
            }

            String batchKey = java.util.UUID.randomUUID().toString();
            String actionUrl = "/indicators/" + indicatorId;
            for (Long recipientId : recipientIds) {
                jdbcTemplate.update(
                        """
                        INSERT INTO public.sys_user_notification (
                            recipient_user_id, sender_user_id, sender_org_id,
                            notification_type, title, content, status,
                            action_url, related_entity_type, related_entity_id, batch_key
                        ) VALUES (?, ?, ?, ?, ?, ?, 'UNREAD', ?, 'INDICATOR', ?, ?)
                        """,
                        recipientId,
                        operatorUserId,
                        strategyOrgProperties.getStrategyOrgId(),
                        MUTATION_LOCK_NOTICE_TYPE,
                        MUTATION_LOCK_NOTICE_TITLE,
                        MUTATION_LOCK_NOTICE_CONTENT,
                        actionUrl,
                        indicatorId,
                        batchKey
                );
            }
            log.info("[IndicatorMutation] 锁死通知已写入: indicatorId={}, orgId={}, recipients={}",
                    indicatorId, targetOrgId, recipientIds.size());
        } catch (Exception ex) {
            log.warn("[IndicatorMutation] 锁死通知写入失败（不影响异动发起）: indicatorId={}, error={}",
                    indicatorId, ex.getMessage());
        }
    }

    /** 异动历史（快照列表，前端「已更改 N 次」悬浮展示）。 */
    public List<Map<String, Object>> history(Long indicatorId) {
        return jdbcTemplate.queryForList(
                """
                SELECT log_id, action, before_json, after_json, changed_fields,
                       actor_user_id, created_at
                FROM public.audit_log
                WHERE entity_type = 'INDICATOR' AND entity_id = ?
                ORDER BY created_at DESC
                """,
                indicatorId);
    }

    /** 处于异动中的指标清单（看板异动汇总 + 全面锁死判定）。 */
    public List<Map<String, Object>> listInMutation() {
        return jdbcTemplate.queryForList(
                """
                SELECT id, indicator_desc, weight_percent, target_org_id,
                       mutation_status, mutation_started_at
                FROM public.indicator
                WHERE mutation_status = 'IN_MUTATION' AND COALESCE(is_deleted, false) = false
                ORDER BY mutation_started_at DESC
                """);
    }

    /** 全面锁死：该组织下任一指标在异动中，即视为锁死。 */
    public boolean isOrgLockedByMutation(Long orgId) {
        if (orgId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.indicator
                WHERE mutation_status = 'IN_MUTATION'
                  AND COALESCE(is_deleted, false) = false
                  AND (target_org_id = ? OR owner_org_id = ?)
                """,
                Integer.class, orgId, orgId);
        return count != null && count > 0;
    }

    private void insertAuditLog(Long indicatorId,
                                Map<String, Object> before,
                                Map<String, Object> after,
                                Map<String, Object> changedFields,
                                Long operatorUserId) {
        jdbcTemplate.update(
                """
                INSERT INTO public.audit_log (
                    action, before_json, after_json, changed_fields,
                    created_at, entity_id, entity_type, reason, actor_user_id
                )
                VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb, CURRENT_TIMESTAMP, ?, 'INDICATOR', ?, ?)
                """,
                "UPDATE",
                toJson(before),
                toJson(after),
                toJson(changedFields),
                indicatorId,
                "指标异动",
                operatorUserId == null ? null : operatorUserId
        );
    }

    private String toJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化异动快照失败", e);
        }
    }

    private java.math.BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.math.BigDecimal bd) {
            return bd;
        }
        return new java.math.BigDecimal(String.valueOf(value));
    }
}
