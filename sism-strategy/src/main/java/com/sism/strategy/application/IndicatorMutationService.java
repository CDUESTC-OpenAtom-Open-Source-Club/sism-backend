package com.sism.strategy.application;

import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.interfaces.dto.StartWorkflowRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /** 2026-10-21：战略任务改名异动与指标异动同链，实体类型为 TASK。 */
    private static final String TASK_ENTITY_TYPE = "TASK";
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
    /** 2026-10-21：任务改名异动复用任务变更留痕（audit_log entity_type=TASK）。 */
    private final com.sism.task.application.TaskMutationHistoryService taskMutationHistoryService;

    /**
     * 异动发起权只属于填报人（2026-10-21 用户定案）。
     *
     * 口径：从草稿计划到已下发计划，能改数据的始终只有填报人——草稿阶段是他填报，
     * 已下发之后是他发起异动；负责人和校领导只做审批，不编辑任何字段。
     * 因此要求：① 属于战略发展部组织；② 具备 ROLE_REPORTER。
     *
     * 历史沿革：2026-10-18 先限定「战略部组织内成员」，2026-10-20 补排除了分管校领导，
     * 但负责人（ROLE_STRATEGY_DEPT_HEAD）仍能发起，等于可以自己发起自己审批。
     * 本次统一收紧为「必须是填报人」，前后端口径一致。
     */
    public boolean canInitiate(Long operatorUserId) {
        if (operatorUserId == null || operatorUserId <= 0) {
            return false;
        }
        Long orgId = userProvider.getUserOrgId(operatorUserId).orElse(null);
        if (orgId == null || orgId != strategyOrgProperties.getStrategyOrgId()) {
            return false;
        }
        List<String> roleCodes = jdbcTemplate.queryForList("""
                SELECT r.role_code
                FROM public.sys_user_role ur
                JOIN public.sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                """, String.class, operatorUserId);
        return roleCodes.stream()
                .anyMatch(code -> "ROLE_REPORTER".equalsIgnoreCase(String.valueOf(code).trim()));
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
            throw new SecurityException("仅战略发展部填报人可发起异动");
        }

        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT i.id, i.indicator_desc, i.weight_percent, i.remark, i.status,
                       i.mutation_status, p.status AS plan_status
                FROM public.indicator i
                LEFT JOIN public.sys_task t ON t.task_id = i.task_id
                LEFT JOIN public.plan p ON p.id = t.plan_id
                WHERE i.id = ? AND COALESCE(i.is_deleted, false) = false
                FOR UPDATE OF i
                """,
                indicatorId);
        assertPlanDistributedForIndicator(current, "新增");
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该指标已有异动在审批中");
        }
        assertReportApprovalNotInFlightForIndicator(indicatorId);
        assertNoActiveMutationFlow(INDICATOR_ENTITY_TYPE, indicatorId, "指标");

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

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId, null);

        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET mutation_status = ?, mutation_started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                MUTATION_STATUS_IN_MUTATION, indicatorId);

        // 新增场景同样要求权重闭合（新增后合计必须仍为 100）
        assertPlanBasicWeightStillClosed(List.of(indicatorId), Set.of());
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
     * 任务改名条目：{id: 任务ID, entity_type: 'TASK', name: 新任务名}
     */
    @Transactional
    public List<String> initiateBatch(List<Map<String, Object>> items, Long operatorUserId) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("异动内容为空：至少提供一条变更");
        }
        if (!canInitiate(operatorUserId)) {
            throw new SecurityException("仅战略发展部填报人可发起异动");
        }
        // 2026-10-21：一次提交的所有快照与审批实例共享同一批次号，
        // 供审批中心把多条改动聚合成一张卡片，也为「放弃异动」按批回滚预留。
        String batchId = java.util.UUID.randomUUID().toString();
        List<String> instanceIds = new ArrayList<>();
        List<MutationTarget> applied = new ArrayList<>();
        // 权重闭合校验只认指标条目：任务改名不改变权重
        List<Long> touchedIndicatorIds = new ArrayList<>();
        // 2026-10-23：发起不再原地落库后，修改条目的拟变更权重以 override 参与闭合校验
        Map<Long, java.math.BigDecimal> proposedWeights = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            Long entityId = toLongId(item.get("id"));
            if (entityId == null || entityId <= 0) {
                throw new IllegalArgumentException("异动条目缺少有效实体 ID");
            }
            boolean isTask = isTaskItem(item);
            if (isTask) {
                String newName = item.get("name") == null ? null : String.valueOf(item.get("name"));
                applyAndMarkTaskInMutation(entityId, newName, operatorUserId, batchId);
                applied.add(new MutationTarget(TASK_ENTITY_TYPE, entityId, "MODIFY"));
                continue;
            }
            touchedIndicatorIds.add(entityId);
            boolean create = Boolean.TRUE.equals(item.get("create"));
            if (create) {
                markCreatedIndicatorInMutation(entityId, operatorUserId, batchId);
                applied.add(new MutationTarget(INDICATOR_ENTITY_TYPE, entityId, "CREATE"));
                continue;
            }
            // 2026-10-21 用户定案：已下发计划上的删除同样走异动审批（软删除在审批通过后才生效）
            if (Boolean.TRUE.equals(item.get("delete"))) {
                markIndicatorForDeletion(entityId, operatorUserId, batchId);
                applied.add(new MutationTarget(INDICATOR_ENTITY_TYPE, entityId, "DELETE"));
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
                        "异动内容为空：指标 " + entityId + " 未提供有效变更字段");
            }
            proposedWeights.putAll(collectProposedWeights(entityId, changes));
            applyAndMarkInMutation(entityId, changes, operatorUserId, batchId);
            applied.add(new MutationTarget(INDICATOR_ENTITY_TYPE, entityId, "MODIFY"));
        }
        // 统一权重闭合校验：按条目各自的（计划、目标部门）去重后逐对校验。
        // 删除条目同样作为校验入口，但其权重先从合计里剔除（删除审批通过前仍留在表里）——
        // 「删一条 + 加/调平一条」照常通过，「只删不补」会被拦下要求同批补齐。
        Set<Long> pendingDeleteIds = applied.stream()
                .filter(target -> INDICATOR_ENTITY_TYPE.equals(target.entityType()))
                .filter(target -> "DELETE".equals(target.mutationType()))
                .map(MutationTarget::entityId)
                .collect(java.util.stream.Collectors.toSet());
        List<Long> weightAnchorIds = touchedIndicatorIds.stream()
                .filter(id -> !pendingDeleteIds.contains(id))
                .toList();
        assertPlanBasicWeightStillClosed(weightAnchorIds, pendingDeleteIds, proposedWeights);
        // 全部通过后逐条启动审批链
        for (MutationTarget target : applied) {
            instanceIds.add(startMutationWorkflow(
                    target.entityType(), target.entityId(), operatorUserId, target.mutationType(), batchId));
        }
        return instanceIds;
    }

    /** 批量异动中已应用的条目，用于全部校验通过后统一启动审批链。 */
    private record MutationTarget(String entityType, Long entityId, String mutationType) {
    }

    private boolean isTaskItem(Map<String, Object> item) {
        Object raw = item.get("entity_type");
        return raw != null && TASK_ENTITY_TYPE.equalsIgnoreCase(String.valueOf(raw).trim());
    }

    /**
     * 2026-10-22 用户定案：异动资格按「整个计划」判定，不按单条指标状态判定。
     *
     * 原实现读 indicator.status 并要求 DISTRIBUTED，导致两个连锁故障：
     * ① 已下发计划里通过「新增行」建出来的指标状态是 DRAFT（新增落库即 DRAFT，
     *    只有整份计划下发时 syncIndicatorStatusWithPlan 才会统一刷成 DISTRIBUTED），
     *    于是同批次「删一条 + 增一条」在提交时被这条 DRAFT 指标挡住，
     *    前端报「仅已下发（稳定态）的指标可发起异动」——而用户看到的明明是已下发计划；
     * ② 新增指标审批通过后仍停在 DRAFT，后续再也无法对它发起修改/删除异动。
     *
     * 判定口径与任务改名异动保持一致（见 applyAndMarkTaskInMutation）：只看所属计划是否
     * DISTRIBUTED。计划已下发即代表这棵指标树对外生效，树内单条指标的 status 只是
     * 下发时的快照，不构成异动门槛。
     */
    private void assertPlanDistributedForIndicator(Map<String, Object> indicatorRow, String actionLabel) {
        String planStatus = indicatorRow.get("plan_status") == null
                ? null
                : String.valueOf(indicatorRow.get("plan_status"));
        if (planStatus == null || "null".equals(planStatus)) {
            // 没有挂到任务/计划的指标（游离指标）不属于已下发树，维持原有拦截
            throw new IllegalStateException(
                    "该指标未关联任何已下发计划，不能发起" + (actionLabel == null ? "" : actionLabel) + "异动");
        }
        if (!"DISTRIBUTED".equalsIgnoreCase(planStatus)) {
            throw new IllegalStateException(
                    "仅已下发（稳定态）的计划可发起" + (actionLabel == null ? "" : actionLabel) + "异动");
        }
    }

    /**
     * 2026-10-23 用户定案（并发缺陷修复）：同一实体同时只允许一条进行中的异动审批。
     * 仅靠 mutation_status 标记拦不住所有路径——驳回退回填报人/中间审批环节时，
     * 审批实例仍处于 IN_REVIEW 活动态，而解锁逻辑此时已清掉 mutation_status，
     * 再次发起就会给同一对象挂上第二条未完成流程（重复创建审批实例、产生脏卡片）。
     * 因此发起前必须再查审批实例表兜底，发现 IN_REVIEW 活动实例一律拦下。
     */
    /**
     * 2026-10-24 用户定案（上报/异动互斥）：同一目标部门上「上报审批在途」与「异动审批在途」
     * 二选一——上报在审期间该部门的填报内容正在被审批固化，不允许异动改动表内容。
     * 判定口径：该指标所属计划（按 task→plan 回查）存在 PLAN_REPORT 审批实例 IN_REVIEW。
     */
    private void assertReportApprovalNotInFlightForIndicator(Long indicatorId) {
        Long planId = jdbcTemplate.query(
                """
                SELECT t.plan_id
                FROM public.indicator i
                JOIN public.sys_task t ON t.task_id = i.task_id
                WHERE i.id = ?
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                indicatorId);
        if (planId == null) {
            return;
        }
        assertReportApprovalNotInFlightForPlan(planId);
    }

    /**
     * 2026-10-27 修复（上报/异动互斥对齐）：任务改名异动路径此前缺失上报在途校验——
     * 指标新增/修改/删除三路径均有 assertReportApprovalNotInFlightForIndicator，
     * 实测上报审批在审时任务改名异动仍可发起，同一计划出现「上报在审 + 异动在审」
     * 两条流程并存。互斥按计划维度判定，此处为指标与任务两条入口的共用核心。
     */
    private void assertReportApprovalNotInFlightForPlan(Long planId) {
        Integer inFlight = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code IN ('PLAN_APPROVAL_FUNCDEPT', 'PLAN_APPROVAL_COLLEGE')
                WHERE ai.entity_type = 'PLAN_REPORT'
                  AND ai.status = 'IN_REVIEW'
                  AND ai.entity_id IN (
                      SELECT pr.id FROM public.plan_report pr WHERE pr.plan_id = ?
                  )
                """,
                Integer.class, planId);
        if (inFlight != null && inFlight > 0) {
            throw new IllegalStateException(
                    "该计划的上报正在审批中，暂不能发起异动；请等上报审批结束或撤回后再操作");
        }
    }

    private void assertNoActiveMutationFlow(String entityType, Long entityId, String label) {
        Integer activeCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                WHERE ai.entity_type = ?
                  AND ai.entity_id = ?
                  AND ai.status = 'IN_REVIEW'
                """,
                Integer.class, entityType, entityId);
        if (activeCount != null && activeCount > 0) {
            throw new IllegalStateException("该" + label
                    + "已有异动审批进行中（同一对象同时只能有一条异动流程），"
                    + "请先在审批中心处理完当前流程后再重新发起");
        }
    }

    /**
     * 2026-10-21 用户定案：战略任务改名与指标字段同构——已下发计划上的任务名
     * 双击改动同样走异动审批。校验口径与指标一致：任务必须存在、所属计划已下发、
     * 且当前没有进行中的任务异动。
     *
     * 2026-10-23 用户定案（状态更新与上报流程对齐）：发起异动只写快照 + 打异动标记，
     * 任务名等审批通过后才落库；驳回/撤回时表里仍是原值，不产生脏数据。
     * SELECT 带 FOR UPDATE 行锁，并发双击/双人同时发起时串行化，后到者被唯一性守卫拦下。
     */
    private void applyAndMarkTaskInMutation(Long taskId, String newName, Long operatorUserId,
                                           String batchId) {
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("战略任务名称不能为空");
        }
        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT t.task_id, t.name, t.plan_id, t.mutation_status, p.status AS plan_status
                FROM public.sys_task t
                JOIN public.plan p ON p.id = t.plan_id
                WHERE t.task_id = ? AND COALESCE(t.is_deleted, false) = false
                FOR UPDATE OF t
                """,
                taskId);
        String planStatus = String.valueOf(current.get("plan_status"));
        if (!"DISTRIBUTED".equalsIgnoreCase(planStatus)) {
            throw new IllegalStateException("仅已下发（稳定态）计划的任务可发起异动");
        }
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该任务已有异动在审批中");
        }
        // 2026-10-27 修复（上报/异动互斥对齐）：任务改名异动补上报在途校验，
        // 与指标三路径（新增/修改/删除）同口径——上报在审期间不允许异动改动表内容。
        Long planId = current.get("plan_id") == null
                ? null
                : ((Number) current.get("plan_id")).longValue();
        if (planId != null) {
            assertReportApprovalNotInFlightForPlan(planId);
        }
        assertNoActiveMutationFlow(TASK_ENTITY_TYPE, taskId, "战略任务");
        String previousName = current.get("name") == null ? null : String.valueOf(current.get("name"));
        if (trimmed.equals(previousName == null ? "" : previousName.trim())) {
            throw new IllegalArgumentException("新任务名称与当前值相同，无需异动");
        }

        taskMutationHistoryService.recordRename(taskId, previousName, trimmed, operatorUserId, batchId);

        jdbcTemplate.update(
                """
                UPDATE public.sys_task
                SET mutation_status = ?, mutation_started_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = ?
                """,
                MUTATION_STATUS_IN_MUTATION, taskId);
    }

    /** 新增指标进入异动：写「(新增)」快照 + 打异动标记（不含权重校验与审批启动）。 */
    private void markCreatedIndicatorInMutation(Long indicatorId, Long operatorUserId, String batchId) {
        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT i.id, i.indicator_desc, i.weight_percent, i.remark, i.status,
                       i.mutation_status, p.status AS plan_status
                FROM public.indicator i
                LEFT JOIN public.sys_task t ON t.task_id = i.task_id
                LEFT JOIN public.plan p ON p.id = t.plan_id
                WHERE i.id = ? AND COALESCE(i.is_deleted, false) = false
                FOR UPDATE OF i
                """,
                indicatorId);
        assertPlanDistributedForIndicator(current, "新增");
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该指标已有异动在审批中");
        }
        assertReportApprovalNotInFlightForIndicator(indicatorId);
        assertNoActiveMutationFlow(INDICATOR_ENTITY_TYPE, indicatorId, "指标");

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

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId, batchId);

        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET mutation_status = ?, mutation_started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                MUTATION_STATUS_IN_MUTATION, indicatorId);
    }

    /**
     * 删除指标进入异动：写 before 快照 + 打异动标记，**不立即软删除**。
     *
     * 2026-10-21 用户定案：已下发计划上的删除与修改、新增一样走异动审批，
     * 审批通过后才真正归档（软删除），驳回则指标保持原样继续生效。
     * 这与「新增指标先落库、审批通过才生效」是同一套口径：
     * 异动期间表里保留的是审批前状态，审批结果决定最终形态。
     */
    private void markIndicatorForDeletion(Long indicatorId, Long operatorUserId, String batchId) {
        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT i.id, i.indicator_desc, i.weight_percent, i.remark, i.status,
                       i.mutation_status, p.status AS plan_status
                FROM public.indicator i
                LEFT JOIN public.sys_task t ON t.task_id = i.task_id
                LEFT JOIN public.plan p ON p.id = t.plan_id
                WHERE i.id = ? AND COALESCE(i.is_deleted, false) = false
                FOR UPDATE OF i
                """,
                indicatorId);
        assertPlanDistributedForIndicator(current, "删除");
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该指标已有异动在审批中");
        }
        assertReportApprovalNotInFlightForIndicator(indicatorId);
        assertNoActiveMutationFlow(INDICATOR_ENTITY_TYPE, indicatorId, "指标");

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("indicator_desc", current.get("indicator_desc"));
        before.put("weight_percent", current.get("weight_percent"));
        before.put("remark", current.get("remark"));
        // 删除没有 after 值，用 "(删除)" 占位，保证快照结构与修改类一致
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("indicator_desc", "(删除)");
        after.put("weight_percent", "(删除)");
        after.put("remark", "(删除)");
        Map<String, Object> changedFields = new LinkedHashMap<>();
        changedFields.put("__delete__", Map.of("before", "(存在)", "after", "(删除)"));

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId, batchId);

        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET mutation_status = ?, mutation_started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                MUTATION_STATUS_IN_MUTATION, indicatorId);
    }

    /** 修改指标进入异动：写 before/after 快照 + 打异动标记（不含权重校验与审批启动）。
     *
     * 2026-10-23 用户定案（状态更新与上报流程对齐）：发起异动只落快照与异动标记，
     * 变更值审批通过后才写入业务表——驳回/撤回时表里仍是原值，不再产生脏数据；
     * 审批详情展示的 before→after 一律以快照为准。SELECT 加 FOR UPDATE 行锁，
     * 并发重复发起时串行化，由唯一性守卫拦下后到请求。
     */
    private void applyAndMarkInMutation(Long indicatorId, Map<String, Object> changes,
                                        Long operatorUserId, String batchId) {
        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT i.id, i.indicator_desc, i.weight_percent, i.remark, i.status,
                       i.mutation_status, p.status AS plan_status
                FROM public.indicator i
                LEFT JOIN public.sys_task t ON t.task_id = i.task_id
                LEFT JOIN public.plan p ON p.id = t.plan_id
                WHERE i.id = ? AND COALESCE(i.is_deleted, false) = false
                FOR UPDATE OF i
                """,
                indicatorId);
        assertPlanDistributedForIndicator(current, "修改");
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该指标已有异动在审批中");
        }
        assertReportApprovalNotInFlightForIndicator(indicatorId);
        assertNoActiveMutationFlow(INDICATOR_ENTITY_TYPE, indicatorId, "指标");

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

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId, batchId);

        // 2026-10-23 用户定案：只打异动标记，不落业务值——审批通过时由
        // StrategyWorkflowBusinessStatusAdapter 按快照 after 值落库。
        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET mutation_status = ?,
                    mutation_started_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                MUTATION_STATUS_IN_MUTATION,
                indicatorId
        );
    }

    /** 启动单条实体的异动审批链（PLAN_MUTATION_STRATEGY，填报人修改 → 战略部负责人 → 分管校领导终审）。 */
    private String startMutationWorkflow(Long entityId, Long operatorUserId, String mutationType) {
        return startMutationWorkflow(INDICATOR_ENTITY_TYPE, entityId, operatorUserId, mutationType, null);
    }

    /**
     * 2026-10-21：任务改名异动与指标异动共用同一条流程定义，
     * 仅 businessEntityType 不同（INDICATOR / TASK），审批节点完全一致。
     */
    private String startMutationWorkflow(String entityType, Long entityId,
                                         Long operatorUserId, String mutationType, String batchId) {
        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setWorkflowCode("PLAN_MUTATION_STRATEGY");
        request.setBusinessEntityId(entityId);
        request.setBusinessEntityType(entityType);
        Map<String, Object> variables = new LinkedHashMap<>();
        if (INDICATOR_ENTITY_TYPE.equals(entityType)) {
            variables.put("indicatorId", entityId);
        } else {
            variables.put("taskId", entityId);
        }
        variables.put("initiatedBy", operatorUserId);
        variables.put("mutationType", mutationType);
        request.setVariables(variables);
        var response = businessWorkflowApplicationService.startWorkflow(
                request, operatorUserId, strategyOrgProperties.getStrategyOrgId());
        // 2026-10-21：把批次号写到审批实例上，供审批中心按批次聚合展示
        if (batchId != null && response.getInstanceId() != null) {
            jdbcTemplate.update(
                    "UPDATE public.audit_instance SET batch_id = ? WHERE id = ?",
                    batchId, toLongId(response.getInstanceId()));
        }
        log.info("[IndicatorMutation] 批量异动已发起: entityType={}, entityId={}, mutationType={}, instanceId={}, batchId={}",
                entityType, entityId, mutationType, response.getInstanceId(), batchId);
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
     * 2026-10-21：批量异动按条目各自的（计划、目标部门）去重后逐对校验，
     * 修复混合批次只校验第一个锚点所属计划、漏检其余计划的缺陷。
     * 2026-10-22：同一批次里待删除的指标不计入合计——删除要等审批通过才归档，
     * 若把它算进去，「删一条 10 分 + 加一条 10 分」会被误判成 110。
     * 2026-10-22 修复（只删不补会把计划卡死）：删除条目同样作为校验入口——
     * 此前校验入口只取「非删除条目」，整批只有删除时入口为空、校验被跳过，
     * 删除通过后计划合计跌到 60，此后连纯改名的异动都被"合计必须为100"拦死。
     * 现在删除批次也按「剔除待删后的合计=100」校验，不满足则要求同批次补齐。
     */
    private void assertPlanBasicWeightStillClosed(List<Long> indicatorIds, Set<Long> pendingDeleteIds) {
        assertPlanBasicWeightStillClosed(indicatorIds, pendingDeleteIds, Map.of());
    }

    private void assertPlanBasicWeightStillClosed(List<Long> indicatorIds, Set<Long> pendingDeleteIds,
                                                  Map<Long, java.math.BigDecimal> weightOverrides) {
        Set<Long> excluded = pendingDeleteIds == null ? Set.of() : pendingDeleteIds;
        Map<Long, java.math.BigDecimal> overrides =
                weightOverrides == null ? Map.of() : weightOverrides;
        Set<Long> pairSourceIds = new LinkedHashSet<>();
        if (indicatorIds != null) {
            pairSourceIds.addAll(indicatorIds);
        }
        pairSourceIds.addAll(excluded);
        if (pairSourceIds.isEmpty()) {
            return;
        }
        Set<String> validatedPairs = new HashSet<>();
        for (Long indicatorId : pairSourceIds) {
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
                continue;
            }
            String pairKey = planId + ":" + targetOrgId;
            if (validatedPairs.add(pairKey)) {
                try {
                    basicTaskWeightValidationService.validatePlanBasicWeight(
                            planId, targetOrgId, excluded, overrides);
                } catch (IllegalStateException ex) {
                    if (excluded.isEmpty()) {
                        throw ex;
                    }
                    // 删除类批次的报错补上「如何修复」的指引：校验数值已剔除待删指标
                    throw new IllegalStateException(
                            ex.getMessage()
                                    + "（本次异动包含删除，校验已剔除待删除指标；"
                                    + "请在同批次内调整其他指标权重补齐到100后重新提交）");
                }
            }
        }
    }

    /**
     * 2026-10-23：发起异动不再原地落库，修改条目的拟变更权重需以 override 形式
     * 参与闭合校验（校验口径 = 本批变更生效后的快照）。仅取 weight_percent 变更。
     */
    private Map<Long, java.math.BigDecimal> collectProposedWeights(Long indicatorId,
                                                                   Map<String, Object> changes) {
        if (changes == null || !changes.containsKey("weight_percent")
                || changes.get("weight_percent") == null) {
            return Map.of();
        }
        java.math.BigDecimal proposed = toBigDecimal(changes.get("weight_percent"));
        if (proposed == null) {
            return Map.of();
        }
        Map<Long, java.math.BigDecimal> overrides = new LinkedHashMap<>();
        overrides.put(indicatorId, proposed);
        return overrides;
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
            throw new SecurityException("仅战略发展部填报人可发起异动");
        }

        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT i.id, i.indicator_desc, i.weight_percent, i.remark, i.status,
                       i.mutation_status, p.status AS plan_status
                FROM public.indicator i
                LEFT JOIN public.sys_task t ON t.task_id = i.task_id
                LEFT JOIN public.plan p ON p.id = t.plan_id
                WHERE i.id = ? AND COALESCE(i.is_deleted, false) = false
                FOR UPDATE OF i
                """,
                indicatorId);
        assertPlanDistributedForIndicator(current, null);
        if (current.get("mutation_status") != null) {
            throw new IllegalStateException("该指标已有异动在审批中");
        }
        assertReportApprovalNotInFlightForIndicator(indicatorId);
        assertNoActiveMutationFlow(INDICATOR_ENTITY_TYPE, indicatorId, "指标");

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

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId, null);

        // 2026-10-23 用户定案：发起只打标记，变更值审批通过后按快照落库（与上报口径一致）
        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET mutation_status = ?,
                    mutation_started_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                MUTATION_STATUS_IN_MUTATION,
                indicatorId
        );

        // 2026-10-20 用户定案：异动与下发共用同一闭合口径——
        // 基础性指标权重合计必须为 100；校验按「本批变更生效后的快照」计算，
        // 修改条目的拟变更权重以 override 形式参与合计（发起不再原地落库）。
        assertPlanBasicWeightStillClosed(List.of(indicatorId), Set.of(), collectProposedWeights(indicatorId, changes));

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
     * 2026-10-21：任务改名异动（entity_type=TASK）并入同一清单，返回 entity_type
     * 供前端按各自实体去取变更历史；并补充流程编码、当前节点与流向组织名，
     * 供审批中心「计划审批」看板直接渲染异动卡片。
     * 2026-10-21 二次：返回 batch_id 与本次异动的字段变更明细（changed_fields），
     * 审批详情弹窗据此逐字段展示 before→after。
     */
    public java.util.List<java.util.Map<String, Object>> listMutationApprovals(Long orgId) {
        if (orgId == null) {
            return java.util.List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT ai.id AS instance_id,
                       ai.entity_id AS indicator_id,
                       ai.entity_type AS entity_type,
                       ai.batch_id AS batch_id,
                       left(COALESCE(i.indicator_desc, t.name), 60) AS indicator_desc,
                       ai.status,
                       ai.created_at,
                       ai.completed_at,
                       'PLAN_MUTATION_STRATEGY' AS flow_code,
                       cur.step_name AS current_step_name,
                       cur.approver_org_id AS approver_org_id,
                       so1.name AS source_org_name,
                       so2.name AS target_org_name,
                       COALESCE(i.owner_org_id, t.created_by_org_id) AS source_org_id,
                       COALESCE(i.target_org_id, t.org_id) AS target_org_id,
                       ru.real_name AS submitter_name,
                       log.changed_fields::text AS changed_fields_json,
                       log.before_json::text AS before_json,
                       log.after_json::text AS after_json
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                LEFT JOIN public.indicator i ON ai.entity_type = 'INDICATOR'
                    AND i.id = ai.entity_id AND COALESCE(i.is_deleted, false) = false
                LEFT JOIN public.sys_task t ON ai.entity_type = 'TASK'
                    AND t.task_id = ai.entity_id AND COALESCE(t.is_deleted, false) = false
                LEFT JOIN LATERAL (
                    SELECT si.step_name, si.approver_org_id
                    FROM public.audit_step_instance si
                    WHERE si.instance_id = ai.id AND si.status = 'PENDING'
                    ORDER BY si.step_no DESC LIMIT 1
                ) cur ON true
                LEFT JOIN public.sys_org so1 ON so1.id = COALESCE(i.owner_org_id, t.created_by_org_id)
                LEFT JOIN public.sys_org so2 ON so2.id = COALESCE(i.target_org_id, t.org_id)
                LEFT JOIN public.sys_user ru ON ru.id = ai.requester_id
                LEFT JOIN LATERAL (
                    SELECT al.changed_fields, al.before_json, al.after_json
                    FROM public.audit_log al
                    WHERE al.entity_type = ai.entity_type
                      AND al.entity_id = ai.entity_id
                      AND (
                            (ai.batch_id IS NOT NULL AND al.batch_id = ai.batch_id)
                         OR (ai.batch_id IS NULL AND al.created_at <= ai.created_at)
                      )
                    ORDER BY al.created_at DESC, al.log_id DESC
                    LIMIT 1
                ) log ON true
                WHERE (i.target_org_id = ? OR i.owner_org_id = ?)
                   OR (t.org_id = ? OR t.created_by_org_id = ?)
                ORDER BY ai.created_at DESC
                LIMIT 50
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("instance_id", rs.getLong("instance_id"));
                    row.put("indicator_id", rs.getLong("indicator_id"));
                    row.put("entity_type", rs.getString("entity_type"));
                    row.put("batch_id", rs.getString("batch_id"));
                    row.put("indicator_desc", rs.getString("indicator_desc"));
                    row.put("status", rs.getString("status"));
                    row.put("created_at", rs.getTimestamp("created_at"));
                    row.put("completed_at", rs.getTimestamp("completed_at"));
                    row.put("flow_code", rs.getString("flow_code"));
                    row.put("current_step_name", rs.getString("current_step_name"));
                    row.put("approver_org_id", rs.getObject("approver_org_id"));
                    row.put("source_org_name", rs.getString("source_org_name"));
                    row.put("target_org_name", rs.getString("target_org_name"));
                    row.put("source_org_id", rs.getObject("source_org_id"));
                    row.put("target_org_id", rs.getObject("target_org_id"));
                    row.put("submitter_name", rs.getString("submitter_name"));
                    row.put("changed_fields", parseJson(rs.getString("changed_fields_json")));
                    row.put("before_json", parseJson(rs.getString("before_json")));
                    row.put("after_json", parseJson(rs.getString("after_json")));
                    return row;
                },
                orgId, orgId, orgId, orgId);
    }

    private void insertAuditLog(Long indicatorId,
                                Map<String, Object> before,
                                Map<String, Object> after,
                                Map<String, Object> changedFields,
                                Long operatorUserId,
                                String batchId) {
        jdbcTemplate.update(
                """
                INSERT INTO public.audit_log (
                    action, before_json, after_json, changed_fields,
                    created_at, entity_id, entity_type, reason, actor_user_id, batch_id
                )
                VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb, CURRENT_TIMESTAMP, ?, 'INDICATOR', ?, ?, ?)
                """,
                "UPDATE",
                toJson(before),
                toJson(after),
                toJson(changedFields),
                indicatorId,
                "指标异动",
                operatorUserId == null ? null : operatorUserId,
                batchId
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
