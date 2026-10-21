package com.sism.strategy.application;

import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.interfaces.dto.StartWorkflowRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5 指标异动（会议定案）：
 * - 原地修改（ID 不变），不复制副本
 * - 每次变更写 audit_log 快照（before_json/after_json/changed_fields）供「已更改 N 次」与历史版本
 * - 异动只能由战略发展部发起（分管校领导终审，PLAN_MUTATION_STRATEGY 三级链）
 * - 2026-10-20 用户定案：异动期间不再锁死其他模块（填报/导入/下发全部放开，人为处理）。
 *   原「全面锁死」口径（isOrgLockedByMutation）已移除——它曾导致战略部对任意部门的
 *   导入被无关异动拦截（线上实例：保卫处指标异动锁死后勤资产处导入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorMutationService {

    private static final String MUTATION_STATUS_IN_MUTATION = "IN_MUTATION";
    private static final String INDICATOR_ENTITY_TYPE = "INDICATOR";
    private static final long STRATEGY_ORG_ID = 35L;

    /** A3 锁死主动通知：标题/文案为会议定案口径。 */
    private static final String MUTATION_LOCK_NOTICE_TITLE = "指标异动审批中";
    private static final String MUTATION_LOCK_NOTICE_CONTENT =
            "上级正在对该部门的指标进行异动审批，期间暂不能填报或提交，请稍后再试";
    private static final String MUTATION_LOCK_NOTICE_TYPE = "INDICATOR_MUTATION";

    private final JdbcTemplate jdbcTemplate;
    /** 2026-10-20：异动同样必须满足基础性权重合计=100 的闭合校验 */
    private final BasicTaskWeightValidationService basicTaskWeightValidationService;
    private final BusinessWorkflowApplicationService businessWorkflowApplicationService;
    private final com.sism.shared.domain.user.UserProvider userProvider;
    private final com.sism.strategy.infrastructure.StrategyOrgProperties strategyOrgProperties;

    /**
     * 2026-10-18 用户定案：异动发起权只属于战略发展部组织下的成员——
     * 不开放给分管校领导等其他角色（分管校领导仅作为异动审批链的终审节点）。
     */
    public boolean canInitiate(Long operatorUserId) {
        if (operatorUserId == null || operatorUserId <= 0) {
            return false;
        }
        Long orgId = userProvider.getUserOrgId(operatorUserId).orElse(null);
        if (orgId == null || orgId != strategyOrgProperties.getStrategyOrgId()) {
            return false;
        }
        // 2026-10-20 修复：分管校领导账号同样挂在战略发展部（org 35），仅按组织判断会放行。
        // 按用户定案「只有战略发展部这个组织下的成员才能发起异动」——校领导只是异动审批链
        // 的终审节点，不具备发起权，因此排除 ROLE_VICE_PRESIDENT。
        List<String> roleCodes = jdbcTemplate.queryForList("""
                SELECT r.role_code
                FROM public.sys_user_role ur
                JOIN public.sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                """, String.class, operatorUserId);
        boolean isVicePresident = roleCodes.stream()
                .anyMatch(code -> "ROLE_VICE_PRESIDENT".equalsIgnoreCase(String.valueOf(code).trim()));
        return !isVicePresident;
    }

    /**
     * 2026-10-20 用户定案：已下发计划新增指标也必须走异动审批链。
     * 做法：先按普通新增落库（状态 DRAFT），随即以「新增」语义发起异动审批——
     * 把该指标标记为 IN_MUTATION 并启动 PLAN_MUTATION_STRATEGY，
     * 审批通过后指标才真正生效；驳回/撤回时由审批链回滚（归档该指标）。
     *
     * 与修改型异动的区别：新增没有 before 值，快照里 before 记为 "(新增)"，
     * 审批链节点与修改型完全一致（填报人修改 → 战略部负责人 → 分管校领导终审）。
     */
    @Transactional
    public String initiateForCreatedIndicator(Long indicatorId, Long operatorUserId) {
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
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该指标已有异动在审批中");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("indicator_desc", "(新增)");
        before.put("weight_percent", "(新增)");
        before.put("remark", "(新增)");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("indicator_desc", current.get("indicator_desc"));
        after.put("weight_percent", current.get("weight_percent"));
        after.put("remark", current.get("remark"));
        Map<String, Object> changedFields = new LinkedHashMap<>();
        changedFields.put("indicator_desc", Map.of("before", "(新增)", "after",
                String.valueOf(current.get("indicator_desc"))));

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId);

        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET mutation_status = ?, mutation_started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                MUTATION_STATUS_IN_MUTATION, indicatorId);

        // 新增场景同样要求权重闭合（新增后合计必须仍为 100）
        assertPlanBasicWeightStillClosed(indicatorId);

        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setWorkflowCode("PLAN_MUTATION_STRATEGY");
        request.setBusinessEntityId(indicatorId);
        request.setBusinessEntityType(INDICATOR_ENTITY_TYPE);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("indicatorId", indicatorId);
        variables.put("initiatedBy", operatorUserId);
        variables.put("mutationType", "CREATE");
        request.setVariables(variables);
        var response = businessWorkflowApplicationService.startWorkflow(
                request, operatorUserId, strategyOrgProperties.getStrategyOrgId());
        log.info("[IndicatorMutation] 新增指标异动已发起: indicatorId={}, instanceId={}",
                indicatorId, response.getInstanceId());
        return response.getInstanceId();
    }

    /**
     * 2026-10-21 用户定案：一次异动可同时调整多条指标——多条字段攒改与新增指标
     * 整批一次提交：先全部应用（原地落库 + 打异动标记），再统一做基础性权重合计
     * =100 校验，最后逐条启动审批链；任一失败整批回滚（@Transactional）。
     * item 结构：{id: 指标ID, create?: true 表示新增指标,
     *           indicator_desc?/weight_percent?/remark?: 变更值}
     */
    @Transactional
    public List<String> initiateBatch(List<Map<String, Object>> items, Long operatorUserId) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("异动内容为空：至少提供一条指标的变更");
        }
        if (!canInitiate(operatorUserId)) {
            throw new SecurityException("仅战略发展部可发起指标异动");
        }
        List<String> instanceIds = new ArrayList<>();
        Long weightCheckAnchor = null;
        for (Map<String, Object> item : items) {
            Long indicatorId = toLongId(item.get("id"));
            if (indicatorId == null || indicatorId <= 0) {
                throw new IllegalArgumentException("异动条目缺少有效指标 ID");
            }
            if (weightCheckAnchor == null) {
                weightCheckAnchor = indicatorId;
            }
            boolean create = Boolean.TRUE.equals(item.get("create"));
            if (create) {
                markCreatedIndicatorInMutation(indicatorId, operatorUserId);
                continue;
            }
            Map<String, Object> changes = new LinkedHashMap<>();
            for (String field : new String[] {"indicator_desc", "weight_percent", "remark"}) {
                if (item.containsKey(field)) {
                    changes.put(field, item.get(field));
                }
            }
            if (changes.isEmpty()) {
                throw new IllegalArgumentException(
                        "异动内容为空：指标 " + indicatorId + " 未提供有效变更字段");
            }
            applyAndMarkInMutation(indicatorId, changes, operatorUserId);
        }
        // 统一权重闭合校验：整批应用后校验一次（任一指标即可反查到所属计划）
        if (weightCheckAnchor != null) {
            assertPlanBasicWeightStillClosed(weightCheckAnchor);
        }
        // 全部通过后逐条启动审批链
        for (Map<String, Object> item : items) {
            Long indicatorId = toLongId(item.get("id"));
            boolean create = Boolean.TRUE.equals(item.get("create"));
            instanceIds.add(startMutationWorkflow(indicatorId, operatorUserId,
                    create ? "CREATE" : "MODIFY"));
        }
        return instanceIds;
    }

    /** 新增指标进入异动：写「(新增)」快照 + 打异动标记（不含权重校验与审批启动）。 */
    private void markCreatedIndicatorInMutation(Long indicatorId, Long operatorUserId) {
        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT id, indicator_desc, weight_percent, remark, status, mutation_status
                FROM public.indicator WHERE id = ? AND COALESCE(is_deleted, false) = false
                """,
                indicatorId);
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该指标已有异动在审批中");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("indicator_desc", "(新增)");
        before.put("weight_percent", "(新增)");
        before.put("remark", "(新增)");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("indicator_desc", current.get("indicator_desc"));
        after.put("weight_percent", current.get("weight_percent"));
        after.put("remark", current.get("remark"));
        Map<String, Object> changedFields = new LinkedHashMap<>();
        changedFields.put("indicator_desc", Map.of("before", "(新增)", "after",
                String.valueOf(current.get("indicator_desc"))));

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId);

        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET mutation_status = ?, mutation_started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                MUTATION_STATUS_IN_MUTATION, indicatorId);
    }

    /** 修改指标进入异动：写 before/after 快照 + 原地应用变更 + 打异动标记（不含权重校验与审批启动）。 */
    private void applyAndMarkInMutation(Long indicatorId, Map<String, Object> changes,
                                        Long operatorUserId) {
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

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("indicator_desc", current.get("indicator_desc"));
        before.put("weight_percent", current.get("weight_percent"));
        before.put("remark", current.get("remark"));

        Map<String, Object> after = new LinkedHashMap<>(before);
        Map<String, Object> changedFields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if ("indicator_desc".equals(field)
                    || "weight_percent".equals(field)
                    || "remark".equals(field)) {
                after.put(field, value);
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("before", before.get(field));
                diff.put("after", value);
                changedFields.put(field, diff);
            }
        }
        if (changedFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "异动内容为空：至少提供一个有效变更字段（indicator_desc/weight_percent/remark）");
        }

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId);

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
    }

    /** 启动单条指标的异动审批链（PLAN_MUTATION_STRATEGY，填报人修改 → 战略部负责人 → 分管校领导终审）。 */
    private String startMutationWorkflow(Long indicatorId, Long operatorUserId, String mutationType) {
        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setWorkflowCode("PLAN_MUTATION_STRATEGY");
        request.setBusinessEntityId(indicatorId);
        request.setBusinessEntityType(INDICATOR_ENTITY_TYPE);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("indicatorId", indicatorId);
        variables.put("initiatedBy", operatorUserId);
        variables.put("mutationType", mutationType);
        request.setVariables(variables);
        var response = businessWorkflowApplicationService.startWorkflow(
                request, operatorUserId, strategyOrgProperties.getStrategyOrgId());
        log.info("[IndicatorMutation] 批量异动已发起: indicatorId={}, mutationType={}, instanceId={}",
                indicatorId, mutationType, response.getInstanceId());
        return response.getInstanceId();
    }

    private Long toLongId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 2026-10-20：异动后校验基础性权重闭合（合计=100）。
     * 指标所属计划/目标部门由 task→plan 反查；校验失败抛 IllegalStateException，
     * 由 @Transactional 回滚本次权重变更（不会留下"改了一半"的数据）。
     */
    private void assertPlanBasicWeightStillClosed(Long indicatorId) {
        Long planId = jdbcTemplate.query(
                """
                SELECT t.plan_id
                FROM public.indicator i
                JOIN public.sys_task t ON t.task_id = i.task_id
                WHERE i.id = ?
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                indicatorId);
        Long targetOrgId = jdbcTemplate.query(
                "SELECT target_org_id FROM public.indicator WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                indicatorId);
        if (planId == null || targetOrgId == null) {
            return;
        }
        basicTaskWeightValidationService.validatePlanBasicWeight(planId, targetOrgId);
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
            if ("indicator_desc".equals(field)
                    || "weight_percent".equals(field)
                    || "remark".equals(field)) {
                after.put(field, value);
                // 2026-10-20 修复 NPE：Map.of 不接受 null 值，而指标原备注/原内容可能为空
                //（错误日志定位：IndicatorMutationService:123 NullPointerException）。
                // 改用 HashMap 允许 null，保证"首次填写备注"这类异动可正常发起。
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("before", before.get(field));
                diff.put("after", value);
                changedFields.put(field, diff);
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

        // 2026-10-20 用户定案：异动与下发共用同一闭合口径——
        // 基础性指标权重合计必须为 100，异动后不达标则不允许发起（抛异常回滚本次变更）。
        assertPlanBasicWeightStillClosed(indicatorId);

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
        // 2026-10-18 用户定案：异动发起不再向被锁组织发站内通知——
        // 通知只随审批中心整体流程发送；锁死本身照常生效（见 assertIndicatorOrgNotLockedByMutation）。
        log.info("[IndicatorMutation] 异动发起，锁死生效（不另发通知）: indicatorId={}", indicatorId);
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
            // T1: 前端无 /indicators/{id} 路由，跳转指标详情会 404；改跳进度页（路由已存在）
            String actionUrl = "/indicators/" + indicatorId + "/progress";
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
        return jdbcTemplate.query(
                """
                SELECT log_id, action, before_json::text AS before_json, after_json::text AS after_json,
                       changed_fields::text AS changed_fields, actor_user_id, created_at
                FROM public.audit_log
                WHERE entity_type = 'INDICATOR' AND entity_id = ?
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("log_id", rs.getLong("log_id"));
                    row.put("action", rs.getString("action"));
                    row.put("before_json", parseJson(rs.getString("before_json")));
                    row.put("after_json", parseJson(rs.getString("after_json")));
                    row.put("changed_fields", parseJson(rs.getString("changed_fields")));
                    row.put("actor_user_id", rs.getObject("actor_user_id"));
                    row.put("created_at", rs.getTimestamp("created_at").toLocalDateTime());
                    return row;
                },
                indicatorId);
    }

    /**
     * jsonb 列经 JdbcTemplate 取出时是 PGobject，直接返回会被序列化成
     * {"type":"jsonb","value":"...","null":false}，前端无法解析。这里显式解析为对象。
     */
    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            log.warn("[IndicatorMutation] 解析快照 JSON 失败: {}", e.getMessage());
            return null;
        }
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

    /**
     * 2026-10-10：该组织的异动审批实例清单（审批中心「异动审批」卡片数据源）。
     * 含进行中与已完成（历史），按发起时间倒序。
     */
    public java.util.List<java.util.Map<String, Object>> listMutationApprovals(Long orgId) {
        if (orgId == null) {
            return java.util.List.of();
        }
        return jdbcTemplate.queryForList(
                """
                SELECT ai.id AS instance_id,
                       ai.entity_id AS indicator_id,
                       left(i.indicator_desc, 60) AS indicator_desc,
                       ai.status,
                       ai.created_at,
                       ai.completed_at
                FROM public.audit_instance ai
                JOIN public.indicator i ON i.id = ai.entity_id
                WHERE ai.entity_type = 'INDICATOR'
                  AND COALESCE(i.is_deleted, false) = false
                  AND (i.target_org_id = ? OR i.owner_org_id = ?)
                ORDER BY ai.created_at DESC
                LIMIT 50
                """,
                orgId, orgId);
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
