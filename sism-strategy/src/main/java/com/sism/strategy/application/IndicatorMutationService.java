package com.sism.strategy.application;

import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.application.WorkflowBusinessStatusSyncService;
import com.sism.workflow.application.support.WorkflowEventDispatcher;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.AuditStepInstance;
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
    // 2026-11-05：原 STRATEGY_ORG_ID = 35L 常量已无引用点（战略部 org id 统一走
    // StrategyOrgProperties#getStrategyOrgId），移除以免与配置项脱钩后成为「假口径」。

    // 2026-10-30：随单条异动入口 initiate 一并移除 A3 锁死主动通知的文案常量
    // （notifyLockedOrgUsers 本就无调用点，锁死提示改由审批中心整体流程承载）。

    private final JdbcTemplate jdbcTemplate;
    /** 2026-10-20：异动同样必须满足基础性权重合计=100 的闭合校验 */
    private final BasicTaskWeightValidationService basicTaskWeightValidationService;
    private final BusinessWorkflowApplicationService businessWorkflowApplicationService;
    private final com.sism.shared.domain.user.UserProvider userProvider;
    private final com.sism.strategy.infrastructure.StrategyOrgProperties strategyOrgProperties;
    /**
     * 2026-11-05（学院指标直发改造）：下发/异动互斥守卫的显式口径实现。
     * 原实现内联在本类里、且依赖 {@code plan.created_by_org_id} 的旧语义，改造后会静默失效。
     */
    private final CollegeDispatchGuardService collegeDispatchGuardService;
    /** 2026-10-21：任务改名异动复用任务变更留痕（audit_log entity_type=TASK）。 */
    private final com.sism.task.application.TaskMutationHistoryService taskMutationHistoryService;
    /**
     * 2026-10-31（重发原地续链）：退回异动链的原地复活走既有 resume 域逻辑
     * （AuditInstance.reactivateWithdrawnStep，与 withdrawToRequester 撤回新形态
     * 匹配、零新增行），落库/业务同步/事件广播与 RejectWorkflowUseCase 同序：
     * save → syncAfterWorkflowChanged → workflowEventDispatcher.publish。
     */
    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowBusinessStatusSyncService workflowBusinessStatusSyncService;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    /**
     * 2026-10-31 缺陷修复（原样重发后审批人无待办通知）：重发续链成功后向下一环节
     * 候选审批人补发与首发起同口径的「待审批」通知。首发起的上报链经
     * PlanSubmittedForApprovalEvent → PlanWorkflowEventListener 走同一通知方法，
     * 异动链直连 startWorkflow 无事件监听、publish 对异动实例恒为空事件
     * （AuditInstance 不注册领域事件）——重发路径此前整条通知链路缺位。
     */
    private final com.sism.iam.application.service.UserNotificationService userNotificationService;
    private final com.sism.workflow.application.support.ApproverResolver approverResolver;
    private final com.sism.workflow.application.definition.WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final com.sism.workflow.application.query.WorkflowReadModelService workflowReadModelService;
    /**
     * 2026-11-14（新增行不落库改造）：学院页签「新增行」在点「发起异动」前是纯前端内存
     * 草稿（刷新即丢、不进数据库）。发起异动时前端把新增行的完整创建字段放进 create 条目，
     * 本服务在同一事务内先经 {@link StrategyApplicationService#createIndicator} 落库
     * （skipDistributedWeightValidation=true——单条即校验跳过，闭合校验由批次末尾
     * assertPlanBasicWeightStillClosed 统一做），再走既有 markCreatedIndicatorInMutation
     * 打 IN_MUTATION 标记 + 写「(新增)」快照。
     */
    private final StrategyApplicationService strategyApplicationService;
    /**
     * 2026-11-14（新增行不落库改造）：同事务创建分支需要以 SysOrg 实体调用
     * createIndicator——归属组织=发起人组织（canInitiate 已保证=战略发展部），
     * 目标组织=父级核心指标的 target_org_id。
     */
    private final com.sism.organization.domain.OrganizationRepository organizationRepository;
    /**
     * 2026-11-14（新增行不落库改造）：createIndicator 走 JPA persist（SEQUENCE 主键，
     * INSERT 延迟到 flush 才发到连接上），而同一事务内紧随其后的
     * markCreatedIndicatorInMutation 与 assertPlanBasicWeightStillClosed 用
     * JdbcTemplate 原生 SQL 读回新行——原生查询不经过 Hibernate、不会触发自动 flush，
     * 不显式 flush 读回必然查不到（EmptyResultDataAccessException）。落库后必须先
     * flush 把 indicator/sys_task/plan 新行推到当前事务连接上。
     */
    private final jakarta.persistence.EntityManager entityManager;

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
                       i.mutation_status, p.status AS plan_status, p.id AS plan_id
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
        assertDispatchApprovalNotInFlightForIndicator(indicatorId);
        assertNoActiveMutationFlow(INDICATOR_ENTITY_TYPE, indicatorId, "指标");

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("indicator_desc", "(新增)");
        before.put("weight_percent", "(新增)");
        before.put("remark", "(新增)");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("indicator_desc", current.get("indicator_desc"));
        after.put("weight_percent", current.get("weight_percent"));
        after.put("remark", current.get("remark"));
        // 2026-10-30 缺陷修复（新增异动角标按字段过滤）：changed_fields 必须包含
        // indicator_desc/weight_percent/remark 三个键——前端「权重」「下发备注」列的
        // 「(新增)」角标按各自字段键匹配审计记录，只落 indicator_desc 会导致
        // 权重/备注填了值但圆点不显示。before 统一记 "(新增)"，after 与指标当前值一致
        // （weight_percent 等数值列沿用 String.valueOf 转字符串）。
        Map<String, Object> changedFields = new LinkedHashMap<>();
        changedFields.put("indicator_desc", Map.of("before", "(新增)", "after",
                String.valueOf(current.get("indicator_desc"))));
        changedFields.put("weight_percent", Map.of("before", "(新增)", "after",
                String.valueOf(current.get("weight_percent"))));
        changedFields.put("remark", Map.of("before", "(新增)", "after",
                String.valueOf(current.get("remark"))));

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId, null);

        markIndicatorInMutation(indicatorId);

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
     *
     * <p><b>2026-11-14（新增行不落库改造）前端契约——带完整创建字段的 create 条目：</b>
     * 学院页签「新增行」在点「发起异动」前是纯前端内存草稿（刷新即丢、不落库），
     * 发起异动时新增行按以下结构整行提交（键名定死，camelCase，与既有 batch 条目的
     * snake_case 变更字段并存不冲突）：
     * <pre>
     * { create: true, indicatorDesc, weightPercent, indicatorType,
     *   parentIndicatorId, taskId?, remark? }
     * </pre>
     * <ul>
     *   <li>indicatorDesc（必填，空抛「新增指标内容不能为空」）；</li>
     *   <li>weightPercent（可省，缺省落库为 100，由闭合校验把关）；</li>
     *   <li>indicatorType（定量/定性；与父级一致由前端保证，后端不做强校验）；</li>
     *   <li>parentIndicatorId（必填，父级核心指标，缺失抛「新增指标缺少关联的核心指标」）；</li>
     *   <li>taskId（可省略——后端按父级所属 FUNC_TO_COLLEGE 计划解析/创建任务行）；</li>
     *   <li>remark（可省略）。</li>
     * </ul>
     * 该条目<b>不需要 id</b>（新增行还没有数据库 id）；即使带了 id 也按新建处理、原值忽略。
     * 后端在同一事务内：创建指标落库 → 打 mutation_status=IN_MUTATION → 快照 audit_log
     * （before 全 "(新增)"）→ 批次闭合权重校验 → 启动 PLAN_MUTATION_STRATEGY 审批链。
     * 分流识别：create=true 且（带创建字段 indicatorDesc/indicator_desc，或无有效实体 id）
     * → 同事务创建分支——无 id 的 create 意图条目即使漏带字段也在此分支拿到
     * 「新增指标内容不能为空」/「新增指标缺少关联的核心指标」的明确报错；
     * 仅 {@code {id, create:true}} 无创建字段 = 旧格式（行已落库），走既有按 id 读回分支，
     * 向后兼容。同批同父级多条 create 各自独立创建（SEQUENCE 主键自然递增去重）。
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
            // 2026-11-14（新增行不落库改造）：create 条目分流——带创建字段（或没有有效实体
            // id 的 create 意图条目，即前端内存草稿行）走同事务创建分支，先创建落库再打异动
            // 标记；必须放在「缺少有效实体 ID」守卫之前，否则无 id 的新增行会被误拦。
            // 仅 {id, create:true}（行已落库）= 旧格式，落到下方既有读回分支，向后兼容。
            if (Boolean.TRUE.equals(item.get("create"))
                    && (hasCreateDraftFields(item) || isMissingValidEntityId(item))) {
                Long newIndicatorId = createAndMarkNewIndicatorInMutation(item, operatorUserId, batchId);
                applied.add(new MutationTarget(INDICATOR_ENTITY_TYPE, newIndicatorId, "CREATE"));
                touchedIndicatorIds.add(newIndicatorId);
                continue;
            }
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
                // 2026-11-14：无创建字段的 {id, create:true} = 旧格式（行已落库），保持原分支
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

    // ==================== 2026-11-14（新增行不落库改造）：同事务创建分支 ====================

    /**
     * create 条目是否携带完整创建字段（= 前端内存草稿行，后端需同事务落库）。
     *
     * <p>识别以指标内容字段为准：{@code indicatorDesc}（前端契约主键）或旧别名
     * {@code indicator_desc}。<b>不认 {@code name}</b>——那是任务改名条目的键，
     * 纳入识别会让未来任务/指标键位冲突时误判成新增。
     */
    private boolean hasCreateDraftFields(Map<String, Object> item) {
        return textOf(item, "indicatorDesc", "indicator_desc") != null;
    }

    /**
     * 条目是否缺少有效实体 id。create=true 且无有效 id 的条目只能是前端内存草稿
     * （新口径下新增行没有数据库 id）——即使漏带创建字段也必须进同事务创建分支，
     * 让缺失的 indicatorDesc/parentIndicatorId 拿到「新增指标内容不能为空」/
     * 「新增指标缺少关联的核心指标」的明确报错，而不是笼统的「缺少有效实体 ID」。
     * 带有效 id 且无创建字段的 {id, create:true} = 旧格式（行已落库），不受影响。
     */
    private boolean isMissingValidEntityId(Map<String, Object> item) {
        Long id = toLongId(item.get("id"));
        return id == null || id <= 0;
    }

    /**
     * 同事务「创建指标 → 打异动标记」：
     *
     * <ol>
     *   <li>解析创建字段（indicatorDesc/parentIndicatorId 必填，缺失明确报错；其余可省）；</li>
     *   <li>组织与周期锚点：归属组织=发起人组织（{@link #canInitiate} 已保证=战略发展部）、
     *       目标组织=父级核心指标 target_org_id、周期=父级所属计划 cycle_id；</li>
     *   <li>复用 {@link StrategyApplicationService#createIndicator} 12 参重载
     *       （skipDistributedWeightValidation=true——单条即校验跳过，闭合校验由批次末尾
     *       assertPlanBasicWeightStillClosed 统一做）。已下发 FUNC_TO_COLLEGE 计划场景，
     *       createIndicator 内部经 findOrCreatePlan 命中既有 DISTRIBUTED 计划、走
     *       「转入异动审批口径」分支（既有 log.info 那条），随后新指标挂到该计划的任务行上，
     *       与 markCreatedIndicatorInMutation 的前置校验（计划须 DISTRIBUTED）兼容；</li>
     *   <li>显式 flush（JPA INSERT 延迟到 flush，原生 SQL 读回前必须推到事务连接，
     *       见字段 entityManager 的注释）；</li>
     *   <li>复用 {@link #markCreatedIndicatorInMutation}：前置校验（计划已下发/无在审异动/
     *       上报与下发不在途）+「(新增)」快照 + IN_MUTATION 标记。</li>
     * </ol>
     *
     * <p>同批同父级多条 create 各自独立调用本方法（SEQUENCE 主键自然递增去重）；
     * 任一条失败整批回滚（{@link #initiateBatch} 的 @Transactional）。
     *
     * @return 新建指标的数据库 id（作为本条目的异动实体 id）
     */
    private Long createAndMarkNewIndicatorInMutation(Map<String, Object> item,
                                                     Long operatorUserId, String batchId) {
        String indicatorDesc = textOf(item, "indicatorDesc", "indicator_desc");
        if (indicatorDesc == null || indicatorDesc.isBlank()) {
            throw new IllegalArgumentException("新增指标内容不能为空");
        }
        Long parentIndicatorId = toLongId(valueOf(item, "parentIndicatorId", "parent_indicator_id"));
        if (parentIndicatorId == null || parentIndicatorId <= 0) {
            throw new IllegalArgumentException("新增指标缺少关联的核心指标");
        }
        // 父级核心指标锚点：目标组织（学院）与所属周期都从父级行读——前端内存草稿行
        // 没有这些落库信息，不能信条目自带值（防串周期/串部门）
        Map<String, Object> parent = jdbcTemplate.query(
                """
                SELECT i.target_org_id, p.cycle_id
                FROM public.indicator i
                LEFT JOIN public.sys_task t ON t.task_id = i.task_id
                LEFT JOIN public.plan p ON p.id = t.plan_id
                WHERE i.id = ? AND COALESCE(i.is_deleted, false) = false
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("target_org_id", rs.getObject("target_org_id"));
                    row.put("cycle_id", rs.getObject("cycle_id"));
                    return row;
                },
                parentIndicatorId);
        if (parent == null) {
            throw new IllegalArgumentException("父级核心指标不存在: " + parentIndicatorId);
        }
        Long ownerOrgId = userProvider.getUserOrgId(operatorUserId).orElse(null);
        if (ownerOrgId == null) {
            throw new IllegalArgumentException("责任组织不能为空");
        }
        com.sism.organization.domain.SysOrg ownerOrg = organizationRepository.findById(ownerOrgId)
                .orElseThrow(() -> new IllegalArgumentException("责任组织未找到: " + ownerOrgId));
        // 学院新增行的目标组织不能从父级核心指标直接继承：父级 target_org_id 是归口职能部门，
        // 新指标必须落到父级对应的下游学院。解析器只接受已验证的学院目标，无法确定时拒绝。
        Long targetOrgId = resolveNewIndicatorTargetOrgId(
                item, parentIndicatorId, parent, ownerOrgId);
        com.sism.organization.domain.SysOrg targetOrg = organizationRepository.findById(targetOrgId)
                .orElseThrow(() -> new IllegalArgumentException("目标学院未找到: " + targetOrgId));
        if (targetOrg.getType() != com.sism.organization.domain.OrgType.academic) {
            throw new IllegalArgumentException("无法确定新增子指标的目标学院");
        }
        Long cycleId = toNullableLong(parent.get("cycle_id"));
        Long taskId = toLongId(valueOf(item, "taskId", "task_id"));
        Object weightRaw = item.containsKey("weightPercent")
                ? item.get("weightPercent")
                : item.get("weight_percent");
        java.math.BigDecimal weightPercent = weightRaw == null ? null : toBigDecimal(weightRaw);
        String indicatorType = textOf(item, "indicatorType", "indicator_type");
        String remark = textOf(item, "remark");

        var created = strategyApplicationService.createIndicator(
                indicatorDesc, ownerOrg, targetOrg, taskId, parentIndicatorId,
                indicatorType, weightPercent, null, remark, 0, cycleId, true);
        // JPA INSERT 延迟到 flush——原生 SQL 读回（异动标记/闭合校验/审批链）前必须显式 flush
        entityManager.flush();

        // 复用既有「新增进入异动」逻辑：前置校验 + (新增) 快照 + IN_MUTATION 标记
        markCreatedIndicatorInMutation(created.getId(), operatorUserId, batchId);
        log.info("[IndicatorMutation] 前端内存草稿新增行已同事务落库并进入异动: indicatorId={}, parentIndicatorId={}, targetOrgId={}, batchId={}",
                created.getId(), parentIndicatorId, targetOrgId, batchId);
        return created.getId();
    }

    /**
     * 解析新增学院子指标的目标学院。
     *
     * <p>父级核心指标的 target_org_id 是归口职能部门，不能作为学院子指标的目标组织。
     * 解析顺序为：显式 targetOrgId（前端所选学院）→ 父级现有未删除子指标的唯一目标 →
     * 父级归口职能部门对应的 FUNC_TO_COLLEGE 计划唯一目标。</p>
     *
     * <p>2026-11-14（用户定案「一个归口可下发多个学院」）显式档放宽：目标学院只要
     * 已有该父级的子指标 → 计划必然存在，直接通过（同一父级在多个学院各有子指标
     * 是生产既有形态，如党委办 36 → 工学院 403656 + 计算机学院 403657）；不在
     * 子指标学院集合里时按该学院精确核对下游 FUNC_TO_COLLEGE 计划
     * （{@link #assertDownstreamCollegeContext}，天然支持多学院）。</p>
     *
     * <p>无显式值时的推断兜底保持严格：父级子指标分属多个学院、或归口对应多个
     * 下游学院 → 一律拒绝（猜不出就该拒，不许静默落到某一个学院）。</p>
     */
    private Long resolveNewIndicatorTargetOrgId(Map<String, Object> item,
                                                Long parentIndicatorId,
                                                Map<String, Object> parent,
                                                Long ownerOrgId) {
        Long requestedTargetOrgId = toLongId(valueOf(item, "targetOrgId", "target_org_id"));
        Set<Long> childTargetOrgIds = new LinkedHashSet<>(jdbcTemplate.queryForList(
                """
                SELECT DISTINCT child.target_org_id
                FROM public.indicator child
                JOIN public.sys_org target_org ON target_org.id = child.target_org_id
                WHERE child.parent_indicator_id = ?
                  AND COALESCE(child.is_deleted, false) = false
                  AND child.target_org_id IS NOT NULL
                ORDER BY child.target_org_id
                """,
                Long.class,
                parentIndicatorId));

        if (requestedTargetOrgId != null) {
            assertAcademicTargetOrg(requestedTargetOrgId);
            // 该学院已有此父级的子指标 → 下发计划必然存在，显式目标直接采信
            // （不再因「父级已有多个学院子指标」误拒合法目标——用户定案同一归口
            // 下发多个学院是正确形态，见方法 Javadoc）
            if (childTargetOrgIds.contains(requestedTargetOrgId)) {
                return requestedTargetOrgId;
            }
            // 目标学院尚无该父级的子指标：按该学院精确核对「归口 → 该学院」下游计划
            assertDownstreamCollegeContext(requestedTargetOrgId, parent, ownerOrgId);
            return requestedTargetOrgId;
        }

        if (childTargetOrgIds.size() > 1) {
            throw new IllegalArgumentException("无法确定新增子指标的目标学院：父级已有多个学院子指标");
        }
        if (childTargetOrgIds.size() == 1) {
            Long resolved = childTargetOrgIds.iterator().next();
            assertAcademicTargetOrg(resolved);
            return resolved;
        }

        Long parentFunctionalOrgId = toNullableLong(parent.get("target_org_id"));
        Long cycleId = toNullableLong(parent.get("cycle_id"));
        if (parentFunctionalOrgId != null && cycleId != null) {
            List<Long> downstreamTargets = jdbcTemplate.queryForList(
                    """
                    SELECT DISTINCT p.target_org_id
                    FROM public.plan p
                    JOIN public.sys_org target_org ON target_org.id = p.target_org_id
                    WHERE p.cycle_id = ?
                      AND p.functional_org_id = ?
                      AND p.created_by_org_id = ?
                      AND p.plan_level = 'FUNC_TO_COLLEGE'
                      AND COALESCE(p.is_deleted, false) = false
                      AND target_org.type = 'academic'
                    ORDER BY p.target_org_id
                    """,
                    Long.class,
                    cycleId,
                    parentFunctionalOrgId,
                    ownerOrgId);
            if (downstreamTargets.size() > 1) {
                throw new IllegalArgumentException("无法确定新增子指标的目标学院：父级对应多个下游学院");
            }
            if (downstreamTargets.size() == 1) {
                Long resolved = downstreamTargets.get(0);
                assertAcademicTargetOrg(resolved);
                return resolved;
            }
        }
        throw new IllegalArgumentException("无法确定新增子指标的目标学院");
    }

    private void assertAcademicTargetOrg(Long targetOrgId) {
        com.sism.organization.domain.SysOrg targetOrg = organizationRepository.findById(targetOrgId)
                .orElseThrow(() -> new IllegalArgumentException("目标学院未找到: " + targetOrgId));
        if (targetOrg.getType() != com.sism.organization.domain.OrgType.academic) {
            throw new IllegalArgumentException("无法确定新增子指标的目标学院");
        }
    }

    /** 草稿显式目标必须同时落在父级归口部门对应的学院下游计划中。 */
    private void assertDownstreamCollegeContext(Long targetOrgId,
                                                Map<String, Object> parent,
                                                Long ownerOrgId) {
        Long parentFunctionalOrgId = toNullableLong(parent.get("target_org_id"));
        Long cycleId = toNullableLong(parent.get("cycle_id"));
        if (parentFunctionalOrgId == null || cycleId == null) {
            throw new IllegalArgumentException("无法确定新增子指标的目标学院");
        }
        Integer matchingPlans = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.plan p
                JOIN public.sys_org target_org ON target_org.id = p.target_org_id
                WHERE p.cycle_id = ?
                  AND p.target_org_id = ?
                  AND p.functional_org_id = ?
                  AND p.created_by_org_id = ?
                  AND p.plan_level = 'FUNC_TO_COLLEGE'
                  AND COALESCE(p.is_deleted, false) = false
                  AND target_org.type = 'academic'
                """,
                Integer.class,
                cycleId,
                targetOrgId,
                parentFunctionalOrgId,
                ownerOrgId);
        if (matchingPlans == null || matchingPlans <= 0) {
            throw new IllegalArgumentException("无法确定新增子指标的目标学院");
        }
    }

    /** 按给定键序取第一个非空白文本值（trim 后返回；全缺/空白返回 null）。 */
    private String textOf(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            Object value = item.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    /** 按给定键序取第一个非 null 原始值（数字/文本字段统一入口，缺省返回 null）。 */
    private Object valueOf(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            Object value = item.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
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
        String planId = indicatorRow.get("plan_id") == null
                ? null
                : String.valueOf(indicatorRow.get("plan_id"));
        String planSuffix = planId == null
                ? ""
                : "（该指标当前所属计划 #" + planId + "，状态：" + planStatus + "）";
        if (planStatus == null || "null".equals(planStatus)) {
            // 没有挂到任务/计划的指标（游离指标）不属于已下发树，维持原有拦截
            throw new IllegalStateException(
                    "该指标未关联任何已下发计划，不能发起" + (actionLabel == null ? "" : actionLabel) + "异动"
                            + planSuffix);
        }
        if (!"DISTRIBUTED".equalsIgnoreCase(planStatus)) {
            // 2026-10-28 迭代（Issue #92-1 报错可读性）：带上实际所属计划与状态——
            // 多周期副本下同名指标 id 不同，选错副本时可自助定位
            throw new IllegalStateException(
                    "仅已下发（稳定态）的计划可发起" + (actionLabel == null ? "" : actionLabel) + "异动"
                            + planSuffix);
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
     *
     * <p><b>2026-11-13 用户拍板（上报互斥只锁本部门）</b>：学院上报按归口部门分批提交后，
     * 先反查异动指标自身的归口部门——学院子指标取父级核心指标 target_org_id（单层口径下
     * 100% 有父级），无父级的部门本级指标取自身 target_org_id——只拦「归口相同」的在审上报；
     * A 部门报告在审不再拦 B 部门指标异动。反查不到（游离行等）→ 保守全锁（旧口径）。
     */
    private void assertReportApprovalNotInFlightForIndicator(Long indicatorId) {
        java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT t.plan_id, parent.target_org_id AS parent_target_org_id, i.target_org_id AS target_org_id
                FROM public.indicator i
                JOIN public.sys_task t ON t.task_id = i.task_id
                LEFT JOIN public.indicator parent ON parent.id = i.parent_indicator_id
                WHERE i.id = ?
                """,
                indicatorId);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.get(0);
        Long planId = row.get("plan_id") == null ? null : ((Number) row.get("plan_id")).longValue();
        if (planId == null) {
            return;
        }
        Long parentTargetOrgId = toNullableLong(row.get("parent_target_org_id"));
        Long selfTargetOrgId = toNullableLong(row.get("target_org_id"));
        Long functionalDeptOrgId = parentTargetOrgId != null ? parentTargetOrgId : selfTargetOrgId;
        assertReportApprovalNotInFlightForPlan(planId, functionalDeptOrgId);
    }

    private static Long toNullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 2026-10-27 修复（上报/异动互斥对齐）：任务改名异动路径此前缺失上报在途校验——
     * 指标新增/修改/删除三路径均有 assertReportApprovalNotInFlightForIndicator，
     * 实测上报审批在审时任务改名异动仍可发起，同一计划出现「上报在审 + 异动在审」
     * 两条流程并存。互斥按计划维度判定，此处为指标与任务两条入口的共用核心。
     * 2026-11-13 起为兼容壳：归口未知 → 保守全锁（见重载）。
     */
    private void assertReportApprovalNotInFlightForPlan(Long planId) {
        assertReportApprovalNotInFlightForPlan(planId, null);
    }

    /**
     * 2026-11-13 用户拍板（上报互斥只锁本部门）：只拦「与异动归口部门相同」的在审上报。
     *
     * <ul>
     *   <li>functionalOrgId 非空 → 只统计该计划下 functional_org_id 命中归口（或归口未落值的
     *       存量上报，保守视为相关）的在审 PLAN_REPORT 实例；</li>
     *   <li>functionalOrgId 为空（反查不到，兼容旧数据）→ 保持旧口径全锁，宁可误拦不可漏锁。</li>
     * </ul>
     * SQL 全部参数绑定。
     */
    private void assertReportApprovalNotInFlightForPlan(Long planId, Long functionalOrgId) {
        Integer inFlight;
        if (functionalOrgId == null) {
            inFlight = jdbcTemplate.queryForObject(
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
        } else {
            inFlight = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM public.audit_instance ai
                    JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                        AND f.flow_code IN ('PLAN_APPROVAL_FUNCDEPT', 'PLAN_APPROVAL_COLLEGE')
                    WHERE ai.entity_type = 'PLAN_REPORT'
                      AND ai.status = 'IN_REVIEW'
                      AND ai.entity_id IN (
                          SELECT pr.id FROM public.plan_report pr
                          WHERE pr.plan_id = ?
                            AND (pr.functional_org_id IS NULL OR pr.functional_org_id = ?)
                      )
                    """,
                    Integer.class, planId, functionalOrgId);
        }
        if (inFlight != null && inFlight > 0) {
            throw new IllegalStateException(
                    "该计划的上报正在审批中，暂不能发起异动；请等上报审批结束或撤回后再操作");
        }
    }

    /**
     * 2026-11-09（学院指标单层口径）下发/异动互斥：对<b>学院指标</b>发起异动前，
     * 检查该学院是否正有学院下发审批在途。
     *
     * <p><b>口径演进</b>：
     * <ul>
     *   <li>2026-10-30 初版：职能部门向学院下发审批在途时，战略部不得异动该职能部门自己的指标
     *       （因为下发审的是「部门指标拆给学院」的快照）；</li>
     *   <li>2026-11-05：判定改为「该职能部门正在被下发到学院」，靠
     *       {@code p.created_by_org_id = 本指标目标部门} 圈定 —— 改成战略部下发后该条件恒不成立
     *       → 守卫静默失效（异动照常发起）；</li>
     *   <li>2026-11-09（单层口径）：学院指标改为「战略部 → 学院」直发、<b>没有父级</b>，
     *       职能部门也<b>不再</b>向学院下发指标 —— 「部门指标拆给学院」这层依赖已经不存在，
     *       按部门锚定既会误拦（部门自己的指标在异动，却挡住给学院下发）也会漏拦。
     *       现在唯一还成立的冲突是<b>同一批学院指标</b>自身：
     *       下发审批审的是这批指标的快照，异动会改掉它。</li>
     * </ul>
     *
     * <p><b>为什么这里必须限定 FUNC_TO_COLLEGE</b>：只有归属学院下发计划的指标（学院指标）
     * 才与学院下发审批共享同一份快照。职能部门的指标属于「战略部 → 职能部门」链，
     * 与学院下发审批无关，直接放行 —— 不限定的话，这个守卫要么对职能部门误拦、要么对学院漏拦。
     *
     * <p>仅作用于 INDICATOR 路径；任务改名不改指标值，不在此拦截。
     */
    private void assertDispatchApprovalNotInFlightForIndicator(Long indicatorId) {
        java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT i.target_org_id, p.cycle_id
                FROM public.indicator i
                JOIN public.sys_task t ON t.task_id = i.task_id
                JOIN public.plan p ON p.id = t.plan_id
                -- 2026-11-09（单层口径）：限定指标属于「学院下发计划」（即学院指标）。
                -- 原口径限定 sys_org.type='functional'（目标组织是职能部门）——
                -- 单层后学院指标的目标组织是学院，该条件恒不成立 → rows 为空 → 守卫对学院指标
                -- 整体静默失效（同一批指标既在异动在审又有下发在途，不拦）。
                WHERE i.id = ?
                  AND p.plan_level = 'FUNC_TO_COLLEGE'
                """,
                indicatorId);
        if (rows.isEmpty()) {
            return;
        }
        Object targetOrgId = rows.get(0).get("target_org_id");
        Object cycleId = rows.get(0).get("cycle_id");
        if (!(targetOrgId instanceof Number) || !(cycleId instanceof Number)) {
            return;
        }

        boolean dispatchInFlight = collegeDispatchGuardService.isCollegeDispatchInFlightForOrg(
                ((Number) targetOrgId).longValue(),
                ((Number) cycleId).longValue());
        if (dispatchInFlight) {
            throw new IllegalStateException(
                    "该学院的下发审批正在进行中，暂不能发起异动；请等下发审批结束或撤回后再操作");
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
        // 2026-11-13 用户拍板（上报互斥只锁本部门）：任务改名不改指标值，归口取计划
        // functional_org_id（FUNC_TO_COLLEGE 计划由 V102/导入链/提交补写保证落值）；
        // 取不到时保守全锁。SQL 参数绑定。
        Long planId = current.get("plan_id") == null
                ? null
                : ((Number) current.get("plan_id")).longValue();
        if (planId != null) {
            Long planFunctionalOrgId = jdbcTemplate.query(
                    "SELECT functional_org_id FROM public.plan WHERE id = ? AND functional_org_id IS NOT NULL",
                    (rs, rowNum) -> rs.getLong("functional_org_id"),
                    planId).stream().findFirst().orElse(null);
            assertReportApprovalNotInFlightForPlan(planId, planFunctionalOrgId);
        }
        assertNoActiveMutationFlow(TASK_ENTITY_TYPE, taskId, "战略任务");
        String previousName = current.get("name") == null ? null : String.valueOf(current.get("name"));
        if (trimmed.equals(previousName == null ? "" : previousName.trim())) {
            throw new IllegalArgumentException("新任务名称与当前值相同，无需异动");
        }

        taskMutationHistoryService.recordRename(taskId, previousName, trimmed, operatorUserId, batchId);

        markTaskInMutation(taskId);
    }

    /**
     * 异动标记层（无日志）：仅写异动锁列，不写 audit_log。
     *
     * 列写入口径（与各发起方法原内联 UPDATE 一致，2026-10-31 从四处复制粘贴收拢）：
     * INDICATOR → public.indicator.mutation_status / mutation_started_at / updated_at；
     * TASK → public.sys_task.mutation_status / mutation_started_at / updated_at。
     * 业务列一律不动——修改值落库、新增转正、删除归档（is_deleted）均由审批
     * 通过时的 StrategyWorkflowBusinessStatusAdapter 按快照执行。
     * 2026-10-31（重发原地续链）：重发场景复用本层重建业务锁（重发不写 audit_log）。
     */
    private void markTaskInMutation(Long taskId) {
        jdbcTemplate.update(
                """
                UPDATE public.sys_task
                SET mutation_status = ?, mutation_started_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = ?
                """,
                MUTATION_STATUS_IN_MUTATION, taskId);
    }

    private void markIndicatorInMutation(Long indicatorId) {
        jdbcTemplate.update(
                """
                UPDATE public.indicator
                SET mutation_status = ?, mutation_started_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                MUTATION_STATUS_IN_MUTATION, indicatorId);
    }

    /** 新增指标进入异动：写「(新增)」快照 + 打异动标记（不含权重校验与审批启动）。 */
    private void markCreatedIndicatorInMutation(Long indicatorId, Long operatorUserId, String batchId) {
        Map<String, Object> current = jdbcTemplate.queryForMap(
                """
                SELECT i.id, i.indicator_desc, i.weight_percent, i.remark, i.status,
                       i.mutation_status, p.status AS plan_status, p.id AS plan_id
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
        assertDispatchApprovalNotInFlightForIndicator(indicatorId);
        assertNoActiveMutationFlow(INDICATOR_ENTITY_TYPE, indicatorId, "指标");

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("indicator_desc", "(新增)");
        before.put("weight_percent", "(新增)");
        before.put("remark", "(新增)");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("indicator_desc", current.get("indicator_desc"));
        after.put("weight_percent", current.get("weight_percent"));
        after.put("remark", current.get("remark"));
        // 2026-10-30 缺陷修复：与 initiateForCreatedIndicator 同口径——前端「发起异动」
        // 对新增行走批量入口（create=true → 本方法），changed_fields 只落 indicator_desc
        // 会让「权重」「下发备注」列的「(新增)」角标匹配不到审计记录而不显示。
        Map<String, Object> changedFields = new LinkedHashMap<>();
        changedFields.put("indicator_desc", Map.of("before", "(新增)", "after",
                String.valueOf(current.get("indicator_desc"))));
        changedFields.put("weight_percent", Map.of("before", "(新增)", "after",
                String.valueOf(current.get("weight_percent"))));
        changedFields.put("remark", Map.of("before", "(新增)", "after",
                String.valueOf(current.get("remark"))));

        insertAuditLog(indicatorId, before, after, changedFields, operatorUserId, batchId);

        markIndicatorInMutation(indicatorId);
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
                       i.mutation_status, p.status AS plan_status, p.id AS plan_id
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
        assertDispatchApprovalNotInFlightForIndicator(indicatorId);
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

        markIndicatorInMutation(indicatorId);
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
                       i.mutation_status, p.status AS plan_status, p.id AS plan_id
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
        assertDispatchApprovalNotInFlightForIndicator(indicatorId);
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
        markIndicatorInMutation(indicatorId);
    }

    /** 启动单条实体的异动审批链（PLAN_MUTATION_STRATEGY，填报人修改 → 战略部负责人 → 分管校领导终审）。 */
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

    // 2026-10-30：删除单条异动入口 initiate(indicatorId, changes, operatorUserId)。
    // 原实现写 audit_log 时 batch_id 传 null，而审批通过后的快照落库靠
    // `audit_log.batch_id = audit_instance.batch_id` 定位快照，JOIN 不上 →
    // 变更值永远不落库且审批链显示 APPROVED（静默丢数据）。
    // 业务上异动按「整个计划统一攒改 → 顶部「发起异动」批量提交」走
    // （initiateBatch：audit_log 与 audit_instance 共用同一 batchId，落库正常）。
    // 前端亦从未暴露单条入口（openMutationDialogForField 无调用点）。

    /**
     * 异动历史（快照列表，前端「已更改 N 次」悬浮展示）。
     *
     * 2026-10-31 用户定案（任务F）：被「取消异动」终结的批次没有真正改变数据，
     * 不得计入小圆片「已更改 N 次」——用 NOT EXISTS 排除所在批次存在 CANCELLED
     * 实例的 log 行（不用 JOIN：一个 batch 可含多实体多实例，JOIN 会把 log 行翻倍）。
     * 边界口径：batch_id 为 NULL 的历史行照常计入（NOT EXISTS 天然放行）；
     * WITHDRAWN 退回待重报批次照常计入（只排除 CANCELLED，不扩大）；
     * 重报后 batch 被覆盖的孤儿 log 行照常计入；同指标先取消后重新发起的新批次
     * （新 batch 不命中 CANCELLED）照常计入。审批中心「审批历史」tab 的
     * 「已取消」卡片是流转记录，不受本查询影响。
     */
    public List<Map<String, Object>> history(Long indicatorId) {
        return jdbcTemplate.query(
                """
                SELECT log_id, action, before_json::text AS before_json, after_json::text AS after_json,
                       changed_fields::text AS changed_fields, actor_user_id, created_at
                FROM public.audit_log al
                WHERE al.entity_type = 'INDICATOR' AND al.entity_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM public.audit_instance ai
                      JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                          AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                      WHERE ai.batch_id = al.batch_id
                        AND ai.status = 'CANCELLED'
                  )
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
                       so2.type::text AS target_org_type,
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
                  -- 2026-10-29 收口：本查询的消费方（填报页在途信号/异动在审判定）
                  -- 均只使用 IN_REVIEW 在途项，服务端直接过滤，缩小返回面
                  AND ai.status = 'IN_REVIEW'
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
                    row.put("target_org_type", rs.getString("target_org_type"));
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

    /**
     * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：「退回待重报」的异动链清单
     * （填报人侧「取消异动」按钮与被驳回可感知提示的数据源）。
     *
     * 判定口径：PLAN_MUTATION_STRATEGY 异动实例 status=WITHDRAWN 且 completed_at
     * IS NULL（驳回退回填报人后 RejectWorkflowUseCase 置的活链状态）。2026-10-30
     * 定案修订（异动撤回=退回填报人活链）：发起人在审批中的「撤回」改走
     * withdrawToRequester 转移，同样落 WITHDRAWN + completed_at NULL，撤回后的链
     * 由本查询自然认领（红色横幅/取消异动入口随之可用）；旧 cancel() 终结链
     * （completed_at 非空）仍被天然排除。2026-10-30 二次定案（撤回零新增行）：
     * 退回提交步骤识别改双形态——新形态为发起人撤回把提交类步骤原地置 WITHDRAWN
     * （step_no=1 或审批人=发起人或步骤名含「提交」，与
     * AuditInstance.isRequesterSubmitStep /
     * WorkflowBusinessStatusSyncService.hasReturnedSubmitStep 同口径）；
     * 旧形态为存量数据里追加的退回提交行（同样命中该三口径），只兼容不迁移。
     * 审批中心「异动卡」的 IN_REVIEW 过滤不受本查询影响。
     * 2026-10-30 二次（审批中心退回卡）：补 source_org_name/target_org_name，
     * 连接口径与 {@link #listMutationApprovals} 一致（起=COALESCE(指标责任组织,
     * 任务创建方组织)，止=COALESCE(指标接收组织, 任务所属组织)），审批中心
     * 「退回待重报」状态卡的方向标题据此渲染。
     */
    public java.util.List<java.util.Map<String, Object>> listReturnedMutationChains(Long orgId) {
        if (orgId == null) {
            return java.util.List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT ai.id AS instance_id,
                       ai.entity_id AS indicator_id,
                       ai.entity_type AS entity_type,
                       ai.batch_id AS batch_id,
                       ai.requester_id AS requester_id,
                       left(COALESCE(i.indicator_desc, t.name), 60) AS indicator_desc,
                       ai.status,
                       ai.created_at,
                       rej.reject_comment AS reject_comment,
                       so1.name AS source_org_name,
                       so2.name AS target_org_name,
                       ru.real_name AS submitter_name
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                LEFT JOIN public.indicator i ON ai.entity_type = 'INDICATOR'
                    AND i.id = ai.entity_id AND COALESCE(i.is_deleted, false) = false
                LEFT JOIN public.sys_task t ON ai.entity_type = 'TASK'
                    AND t.task_id = ai.entity_id AND COALESCE(t.is_deleted, false) = false
                LEFT JOIN public.sys_org so1 ON so1.id = COALESCE(i.owner_org_id, t.created_by_org_id)
                LEFT JOIN public.sys_org so2 ON so2.id = COALESCE(i.target_org_id, t.org_id)
                LEFT JOIN public.sys_user ru ON ru.id = ai.requester_id
                LEFT JOIN LATERAL (
                    SELECT si.comment AS reject_comment
                    FROM public.audit_step_instance si
                    WHERE si.instance_id = ai.id AND si.status = 'REJECTED'
                    ORDER BY si.step_no DESC LIMIT 1
                ) rej ON true
                WHERE (
                        (i.target_org_id = ? OR i.owner_org_id = ?)
                        OR (t.org_id = ? OR t.created_by_org_id = ?)
                      )
                  AND ai.status = 'WITHDRAWN'
                  AND ai.completed_at IS NULL
                  AND EXISTS (
                      -- 退回提交步骤双形态（2026-10-30 二次定案，撤回零新增行）：
                      -- 新形态：提交类步骤被发起人撤回原地置 WITHDRAWN（step_no=1/
                      --   审批人=发起人/步骤名含「提交」三口径识别）；
                      -- 旧形态：存量数据里追加的退回提交行（同样命中三口径）。
                      SELECT 1
                      FROM public.audit_step_instance rsi
                      WHERE rsi.instance_id = ai.id
                        AND rsi.status = 'WITHDRAWN'
                        AND (rsi.step_no = 1
                             OR rsi.approver_id = ai.requester_id
                             OR rsi.step_name LIKE '%提交%')
                  )
                ORDER BY ai.created_at DESC
                LIMIT 50
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("instance_id", rs.getLong("instance_id"));
                    row.put("indicator_id", rs.getLong("indicator_id"));
                    row.put("entity_type", rs.getString("entity_type"));
                    row.put("batch_id", rs.getString("batch_id"));
                    row.put("requester_id", rs.getObject("requester_id"));
                    row.put("indicator_desc", rs.getString("indicator_desc"));
                    row.put("status", rs.getString("status"));
                    row.put("created_at", rs.getTimestamp("created_at"));
                    row.put("reject_comment", rs.getString("reject_comment"));
                    row.put("source_org_name", rs.getString("source_org_name"));
                    row.put("target_org_name", rs.getString("target_org_name"));
                    row.put("submitter_name", rs.getString("submitter_name"));
                    return row;
                },
                orgId, orgId, orgId, orgId);
    }

    /**
     * 2026-10-31 用户定案（重定义「重发 = 原地续链」）：一次异动的结束 = 取消异动或
     * 到达最终节点通过；期间重新发起（含原样重发与改后重发）不会生成另一个实例、
     * 不会生成另一个 report、数据库不新增任何数据量——只是把审批节点的状态改回来，
     * 其他数据一概不动。
     *
     * 与旧实现的差异：不再委托 {@link #initiateBatch}（旧路径每次重发都生成新
     * batchId、新增 audit_log 快照行并改写 audit_instance.batch_id，实测同一条
     * CREATE 异动原样重发两次后异动历史冒出 3 条一模一样的记录）。新实现原地续链：
     *
     * ① 校验保留：实例存在、PLAN_MUTATION_STRATEGY 异动链（INDICATOR/TASK）、
     *    requester-only（仅异动发起人，SecurityException）、status=WITHDRAWN 且
     *    completed_at 为空；同批装载口径不变（batch_id 装全部、孤例取自身）。
     * ② 业务锁重建（不写日志）：链离开 IN_REVIEW 时同步器已把 mutation_status 清空
     *    （StrategyWorkflowBusinessStatusAdapter），重发按发起时的列写入口径
     *    （mutation_status / mutation_started_at / updated_at，不含业务列）原地
     *    重新上锁，绝不写 audit_log。
     * ③ 快照不重建：首轮 audit_log 行即权威快照；每个实体必须已有本批快照行
     *    （发起时必写，理论不可能缺失），缺失视为数据异常直接报错，不再静默跳过。
     * ④ 改后重发（items 非空）：按 entity_type+entity_id 匹配批内既有对象后，原地
     *    UPDATE 本批 audit_log 行的 changed_fields / after_json（before_json 保留
     *    首轮原值、action 与 batch_id 均不动，绝不 INSERT 新行，CREATE 行的 before
     *    维持「(新增)」），并用合并后的 after 权重重跑闭合校验（口径与 initiateBatch
     *    一致）；原样重发（items 为空）日志行一个字段都不动，内容未变不必重跑权重校验。
     * ⑤ 逐实例 resume：{@link AuditInstance#reactivateWithdrawnStep()} 原地复活退回
     *    的提交步（与 withdrawToRequester 撤回新形态匹配的既有续链路径，零新增行），
     *    实例回 IN_REVIEW、completedAt 保持 NULL、batch_id 不改写；逐实例
     *    save → syncAfterWorkflowChanged → workflowEventDispatcher.publish（保持
     *    现有通知行为）。
     * ⑥ 通知（2026-10-31 缺陷修复，线上实证 02 号日志异常3）：重发成功后下一环节
     *    候选审批人收到与首发起同口径的「待审批」站内信（首批实例的当前审批节点，
     *    批次只发 1 条，fail-safe）——修复重发后消息中心「待处理/审批通知」双 0。
     *
     * @param items 可选的改后条目（结构同 {@link #initiateBatch}）；null/空 = 原样重发
     */
    public record ResubmitResult(List<String> instanceIds, String message) {
    }

    /** 原地续链的单行上下文：实例 ID、实体、本批快照行（含 log_id）与可选改后条目。 */
    private record RoundRow(Long instanceId, String entityType, Long entityId,
                            Map<String, Object> log, Map<String, Object> item) {
    }

    /** 改后重发的合并结果：行类型 + 合并后的 changed_fields/after_json（before 不动）。 */
    private record MergedLog(String rowType, Map<String, Object> changedFields,
                             Map<String, Object> afterJson, boolean dirty) {
    }

    @Transactional
    public ResubmitResult resubmitBatch(Long instanceId, Long operatorUserId,
                                        List<Map<String, Object>> items) {
        if (instanceId == null || instanceId <= 0) {
            throw new IllegalArgumentException("异动实例 ID 不能为空");
        }
        boolean withDraft = items != null && !items.isEmpty();
        Map<String, Object> instance = jdbcTemplate.query(
                """
                SELECT ai.id, ai.entity_id, ai.entity_type, ai.batch_id, ai.requester_id,
                       ai.status, ai.completed_at, ai.created_at, f.flow_code
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                WHERE ai.id = ?
                """,
                rs -> rs.next() ? mutationInstanceRow(rs) : null,
                instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("异动实例不存在: " + instanceId);
        }
        // 流程范围等价校验（对应 BusinessWorkflowApplicationService.assertMutationChainInstance）
        String flowCode = String.valueOf(instance.get("flow_code"));
        String entityType = String.valueOf(instance.get("entity_type"));
        if (!"PLAN_MUTATION_STRATEGY".equalsIgnoreCase(flowCode)
                || (!"INDICATOR".equalsIgnoreCase(entityType) && !"TASK".equalsIgnoreCase(entityType))) {
            throw new IllegalArgumentException("仅异动审批链支持重新发起操作");
        }
        // requester-only：仅异动发起人可重新发起
        if (!java.util.Objects.equals(toLongId(instance.get("requester_id")), operatorUserId)) {
            throw new SecurityException("仅异动发起人可重新发起");
        }
        // 仅「退回待重报」活链可重发（驳回/撤回退回后的 WITHDRAWN 未完结状态）
        if (!"WITHDRAWN".equalsIgnoreCase(String.valueOf(instance.get("status")))
                || instance.get("completed_at") != null) {
            throw new IllegalStateException("该异动链不在「退回待重报」状态，无法重新发起");
        }

        String batchId = instance.get("batch_id") == null
                ? null
                : String.valueOf(instance.get("batch_id"));
        // 同批全部实例一次续链（与 cancelReturnedMutationBatch 的 findByBatchId 同口径；
        // 无批次号的孤例只续自身）
        List<Map<String, Object>> batchInstances =
                batchId == null || batchId.isBlank()
                        ? List.of(instance)
                        : jdbcTemplate.queryForList(
                                """
                                SELECT ai.id, ai.entity_id, ai.entity_type, ai.batch_id, ai.requester_id,
                                       ai.status, ai.completed_at, ai.created_at, f.flow_code
                                FROM public.audit_instance ai
                                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                                WHERE ai.batch_id = ?
                                """,
                                batchId);

        // ① 逐实例装载本批快照行：新模型下发起时必写快照，缺失即数据异常（报错，不跳过）
        List<RoundRow> rows = new ArrayList<>();
        for (Map<String, Object> row : batchInstances) {
            Long rowInstanceId = toLongId(row.get("id"));
            Long rowEntityId = toLongId(row.get("entity_id"));
            if (rowInstanceId == null || rowInstanceId <= 0
                    || rowEntityId == null || rowEntityId <= 0) {
                throw new IllegalStateException("异动批次存在缺少实体 ID 的实例，无法重新发起");
            }
            String rowEntityType = String.valueOf(row.get("entity_type"));
            Map<String, Object> log = latestRoundAuditLog(rowEntityType, rowEntityId, batchId,
                    (java.sql.Timestamp) row.get("created_at"));
            if (log == null) {
                throw new IllegalStateException(
                        ("TASK".equalsIgnoreCase(rowEntityType) ? "任务#" : "指标#") + rowEntityId
                                + " 缺少本批异动快照，无法重新发起；请「取消异动」后按新内容重新发起");
            }
            rows.add(new RoundRow(rowInstanceId, rowEntityType, rowEntityId, log, null));
        }

        // ② 改后重发：items 只允许指向批内既有对象（按 entity_type+entity_id 匹配，
        //    不新增异动对象——重发绝不产生新实例/新快照行）
        Map<String, Map<String, Object>> unmatchedItems = new LinkedHashMap<>();
        if (withDraft) {
            for (Map<String, Object> item : items) {
                Long itemEntityId = toLongId(item.get("id"));
                if (itemEntityId == null || itemEntityId <= 0) {
                    throw new IllegalArgumentException("重发条目缺少有效实体 ID");
                }
                String itemEntityType = isTaskItem(item) ? TASK_ENTITY_TYPE : INDICATOR_ENTITY_TYPE;
                unmatchedItems.put(itemEntityType + ":" + itemEntityId, item);
            }
            for (int i = 0; i < rows.size(); i++) {
                RoundRow row = rows.get(i);
                Map<String, Object> matched =
                        unmatchedItems.remove(row.entityType() + ":" + row.entityId());
                if (matched != null) {
                    rows.set(i, new RoundRow(row.instanceId(), row.entityType(),
                            row.entityId(), row.log(), matched));
                }
            }
            if (!unmatchedItems.isEmpty()) {
                throw new IllegalArgumentException(
                        "重发条目与原批次不匹配（重发不能新增异动对象）："
                                + String.join("、", unmatchedItems.keySet()));
            }
        }

        // ③ 改后重发的快照合并（内存先行）：before 保留首轮原值，只更新 after 部分
        Map<String, MergedLog> mergedLogs = new LinkedHashMap<>();
        for (RoundRow row : rows) {
            mergedLogs.put(row.entityType() + ":" + row.entityId(),
                    mergeLogForResubmit(row.entityType(), row.log(), row.item()));
        }

        // ④ 权重闭合校验（仅改后重发，内容未变不必重跑；口径与 initiateBatch 一致）：
        //    合并后的 after 权重以 override 参与校验（校验口径 = 本批生效后的快照）；
        //    待删除行同样从合计中剔除
        if (withDraft) {
            Set<Long> pendingDeleteIds = rows.stream()
                    .filter(row -> INDICATOR_ENTITY_TYPE.equalsIgnoreCase(row.entityType()))
                    .filter(row -> "DELETE".equals(
                            mergedLogs.get(row.entityType() + ":" + row.entityId()).rowType()))
                    .map(RoundRow::entityId)
                    .collect(java.util.stream.Collectors.toSet());
            List<Long> weightAnchorIds = rows.stream()
                    .filter(row -> INDICATOR_ENTITY_TYPE.equalsIgnoreCase(row.entityType()))
                    .map(RoundRow::entityId)
                    .filter(id -> !pendingDeleteIds.contains(id))
                    .toList();
            Map<Long, java.math.BigDecimal> proposedWeights = new LinkedHashMap<>();
            for (RoundRow row : rows) {
                if (!INDICATOR_ENTITY_TYPE.equalsIgnoreCase(row.entityType())) {
                    continue;
                }
                Object weightAfter = mergedLogs
                        .get(row.entityType() + ":" + row.entityId())
                        .afterJson().get("weight_percent");
                java.math.BigDecimal proposed =
                        weightAfter == null ? null : toBigDecimalOrNull(weightAfter);
                if (proposed != null) {
                    proposedWeights.put(row.entityId(), proposed);
                }
            }
            assertPlanBasicWeightStillClosed(weightAnchorIds, pendingDeleteIds, proposedWeights);
        }

        // ⑤ 原地改写本批既有日志行（绝不 INSERT；action/before_json/batch_id 均不动）
        if (withDraft) {
            for (RoundRow row : rows) {
                MergedLog merged = mergedLogs.get(row.entityType() + ":" + row.entityId());
                if (!merged.dirty()) {
                    continue;
                }
                Long logId = toLongId(row.log().get("log_id"));
                if (logId == null || logId <= 0) {
                    throw new IllegalStateException(
                            ("TASK".equalsIgnoreCase(row.entityType()) ? "任务#" : "指标#")
                                    + row.entityId() + " 的本批异动快照缺少 log_id，无法原地改写");
                }
                jdbcTemplate.update(
                        """
                        UPDATE public.audit_log
                        SET changed_fields = ?::jsonb, after_json = ?::jsonb
                        WHERE log_id = ?
                        """,
                        toJson(merged.changedFields()), toJson(merged.afterJson()), logId);
            }
        }

        // ⑥ 业务锁重建（不写日志）：链离开 IN_REVIEW 时同步器已清锁，
        //    按发起时的列写入口径原地重新上锁
        for (RoundRow row : rows) {
            if (TASK_ENTITY_TYPE.equalsIgnoreCase(row.entityType())) {
                markTaskInMutation(row.entityId());
            } else {
                markIndicatorInMutation(row.entityId());
            }
        }

        // ⑦ 逐实例原地续链：WITHDRAWN 活链 resume（零新增行、batch_id 不改写），
        //    同批已在审的兄弟实例保持活动；终态实例说明批次状态不一致，整批拒绝
        List<String> instanceIds = new ArrayList<>();
        List<AuditInstance> resumedInstances = new ArrayList<>();
        for (RoundRow row : rows) {
            AuditInstance domain = auditInstanceRepository.findById(row.instanceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "异动实例不存在: " + row.instanceId()));
            String status = String.valueOf(domain.getStatus());
            if (AuditInstance.STATUS_WITHDRAWN.equalsIgnoreCase(status)
                    && domain.getCompletedAt() == null) {
                domain.reactivateWithdrawnStep();
                AuditInstance saved = auditInstanceRepository.save(domain);
                workflowBusinessStatusSyncService.syncAfterWorkflowChanged(saved);
                workflowEventDispatcher.publish(saved);
                resumedInstances.add(saved);
                instanceIds.add(String.valueOf(saved.getId()));
            } else if (AuditInstance.STATUS_PENDING.equalsIgnoreCase(status)) {
                instanceIds.add(String.valueOf(domain.getId()));
            } else {
                throw new IllegalStateException("该异动链不在「退回待重报」状态，无法重新发起");
            }
        }

        // ⑧ 通知（2026-10-31 缺陷修复）：下一环节候选审批人收到与首发起同口径的
        //    「待审批」通知——重发后消息中心/铃铛与首发起一致，不再「待处理 0」
        notifyNextStepCandidatesAfterResume(resumedInstances, operatorUserId);

        String message = withDraft ? "异动已按修改内容重新发起" : "异动已重新发起";
        log.info("[IndicatorMutation] 退回异动已原地续链重发: instanceId={}, batchId={}, 实例数={}, 改后重发={}",
                instanceId, batchId, instanceIds.size(), withDraft);
        return new ResubmitResult(instanceIds, message);
    }

    /**
     * 2026-10-31 缺陷修复（原样重发后审批人无待办通知，线上实证 02 号日志异常3）：
     * 重发续链成功后，复活实例的当前审批节点候选审批人收到与首发起同口径的「待审批」
     * 站内信（createSubmissionNotification：type=APPROVAL_SUBMITTED、标题
     * 「异动审批待处理」、正文「{提交人}提交了“X”，当前待处理环节为“Y”，请尽快审批。」，
     * 与上报链首发起 PlanWorkflowEventListener.notifyNextApprovers 同一通知方法）。
     *
     * 批次口径：一次重发（同批可能多实例、且审批人集合相同）只发 1 条——按批次首个
     * 复活实例解析当前节点候选，与「异动按计划聚合，消息只有一条」的既有定案一致。
     * fail-safe：通知失败仅告警，绝不阻断重发主流程（与 notifyNextStepOnApproval 同策略）。
     */
    private void notifyNextStepCandidatesAfterResume(java.util.List<AuditInstance> resumedInstances,
                                                     Long operatorUserId) {
        if (resumedInstances == null || resumedInstances.isEmpty()) {
            return;
        }
        try {
            AuditInstance representative = resumedInstances.get(0);
            AuditStepInstance currentStep = representative.resolveCurrentPendingStep().orElse(null);
            if (currentStep == null) {
                return;
            }
            AuditStepDef currentStepDef = resolveResumedStepDefinition(representative, currentStep);
            if (currentStepDef == null) {
                return;
            }
            String businessName = workflowReadModelService
                    .getInstanceDetail(String.valueOf(representative.getId()))
                    .getPlanName();
            String submitterName = approverResolver.resolveApproverName(operatorUserId);
            Long senderOrgId = userProvider.getUserOrgId(operatorUserId).orElse(null);
            approverResolver
                    .resolveCandidates(currentStepDef, representative.getRequesterOrgId(), representative)
                    .forEach(candidate -> userNotificationService.createSubmissionNotification(
                            candidate.getUserId(),
                            operatorUserId,
                            senderOrgId,
                            representative.getId(),
                            representative.getEntityType(),
                            representative.getEntityId(),
                            businessName,
                            currentStep.getStepName(),
                            submitterName
                    ));
        } catch (Exception ex) {
            log.warn("[IndicatorMutation] 重发后通知下一环节审批人失败（不影响重发）: instanceIds={}, error={}",
                    resumedInstances.stream()
                            .map(instance -> String.valueOf(instance.getId()))
                            .toList(),
                    ex.getMessage(), ex);
        }
    }

    /** 复活实例当前审批节点定义解析（与 BusinessWorkflowApplicationService.resolveStepDefinition 同口径）。 */
    private AuditStepDef resolveResumedStepDefinition(AuditInstance instance, AuditStepInstance currentStep) {
        AuditFlowDef flowDef = instance.getFlowDefId() == null
                ? null
                : workflowDefinitionQueryService.getAuditFlowDefById(instance.getFlowDefId());
        if (flowDef == null || flowDef.getSteps() == null || flowDef.getSteps().isEmpty()) {
            return null;
        }
        return flowDef.getSteps().stream()
                .filter(step -> step.getId() != null && step.getId().equals(currentStep.getStepDefId()))
                .findFirst()
                .orElse(null);
    }

    /** audit_instance 行（带 flow_code）转 Map，供原样重发校验与批次装载使用。 */
    private Map<String, Object> mutationInstanceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("entity_id", rs.getLong("entity_id"));
        row.put("entity_type", rs.getString("entity_type"));
        row.put("batch_id", rs.getString("batch_id"));
        row.put("requester_id", rs.getObject("requester_id"));
        row.put("status", rs.getString("status"));
        row.put("completed_at", rs.getTimestamp("completed_at"));
        row.put("created_at", rs.getTimestamp("created_at"));
        row.put("flow_code", rs.getString("flow_code"));
        return row;
    }

    /**
     * 取该实体在本轮次（批次）的最近一条异动快照行，含 log_id 供改后重发原地 UPDATE。
     * 行类型无需在此判型——由 {@link #mergeLogForResubmit} 按快照判型（与审批卡详情、
     * StrategyWorkflowBusinessStatusAdapter.resolveLatestMutationType 同源）。无批次号
     * 的孤例（历史数据）回退「不晚于实例发起时间」的最近一条。两条 SQL 均为字面量，
     * 参数全部绑定。
     */
    private Map<String, Object> latestRoundAuditLog(String entityType, Long entityId,
                                                    String batchId, java.sql.Timestamp createdBefore) {
        boolean byBatch = batchId != null && !batchId.isBlank();
        if (byBatch) {
            return jdbcTemplate.query(
                    """
                    SELECT log_id, changed_fields::text AS changed_fields,
                           after_json::text AS after_json, before_json::text AS before_json
                    FROM public.audit_log
                    WHERE entity_type = ? AND entity_id = ? AND batch_id = ?
                    ORDER BY created_at DESC, log_id DESC
                    LIMIT 1
                    """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("log_id", rs.getLong("log_id"));
                        row.put("changed_fields", parseJson(rs.getString("changed_fields")));
                        row.put("after_json", parseJson(rs.getString("after_json")));
                        row.put("before_json", parseJson(rs.getString("before_json")));
                        return row;
                    },
                    entityType, entityId, batchId);
        }
        return jdbcTemplate.query(
                """
                SELECT log_id, changed_fields::text AS changed_fields,
                       after_json::text AS after_json, before_json::text AS before_json
                FROM public.audit_log
                WHERE entity_type = ? AND entity_id = ? AND created_at <= ?
                ORDER BY created_at DESC, log_id DESC
                LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("log_id", rs.getLong("log_id"));
                    row.put("changed_fields", parseJson(rs.getString("changed_fields")));
                    row.put("after_json", parseJson(rs.getString("after_json")));
                    row.put("before_json", parseJson(rs.getString("before_json")));
                    return row;
                },
                entityType, entityId, createdBefore);
    }

    /**
     * 改后重发的快照合并：以首轮 audit_log 行为底，把条目提供的字段值写进
     * changed_fields / after_json 的 after 部分；before_json 保留首轮原值不动
     * （CREATE 行的 before 维持「(新增)」），action / batch_id 均不动。条目未涉及的
     * 既有变更字段原样保留（重发是「同一异动的微调」，不静默丢弃已提交变更）。
     * 行类型按快照判型（与 StrategyWorkflowBusinessStatusAdapter
     * .resolveLatestMutationType 同口径）：changed_fields 含 __delete__ → DELETE；
     * before 含「(新增)」→ CREATE；其余 MODIFY。dirty=条目实际贡献了字段值
     * （才需要原地 UPDATE）。
     */
    @SuppressWarnings("unchecked")
    private MergedLog mergeLogForResubmit(String entityType, Map<String, Object> log,
                                          Map<String, Object> item) {
        Map<String, Object> before = asMap(log.get("before_json"));
        Map<String, Object> after = new LinkedHashMap<>(before);
        Map<String, Object> changed = new LinkedHashMap<>();
        boolean isCreate = false;
        if (log.get("changed_fields") instanceof Map<?, ?> rawChanged) {
            for (Map.Entry<?, ?> entry : rawChanged.entrySet()) {
                String field = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> rawDelta) {
                    Map<String, Object> delta = new LinkedHashMap<>((Map<String, Object>) rawDelta);
                    changed.put(field, delta);
                    if (delta.get("after") != null) {
                        after.put(field, delta.get("after"));
                    }
                    if ("(新增)".equals(String.valueOf(delta.get("before")))) {
                        isCreate = true;
                    }
                }
            }
        }
        String rowType = changed.containsKey("__delete__")
                ? "DELETE"
                : (isCreate ? "CREATE" : "MODIFY");
        boolean dirty = false;
        if (item != null) {
            if (TASK_ENTITY_TYPE.equalsIgnoreCase(entityType)) {
                String newName = item.get("name") == null ? null : String.valueOf(item.get("name"));
                if (newName != null && !newName.isBlank()) {
                    changed.put("name", fieldDelta(before, "name", newName));
                    after.put("name", newName);
                    dirty = true;
                }
            } else {
                for (String field : new String[] {"indicator_desc", "weight_percent", "remark"}) {
                    if (item.get(field) == null) {
                        continue;
                    }
                    Object value = item.get(field);
                    changed.put(field, fieldDelta(before, field, value));
                    after.put(field, value);
                    dirty = true;
                }
            }
        }
        return new MergedLog(rowType, changed, after, dirty);
    }

    /** 字段级 before→after 差量；before 取首轮 before_json 原值（保留不动）。 */
    private Map<String, Object> fieldDelta(Map<String, Object> before, String field,
                                           Object afterValue) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("before", before.get(field));
        delta.put("after", afterValue);
        return delta;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    /** 宽松数值解析（快照 after 值已在一轮发起时校验过），解析失败返回 null 不抛错。 */
    private java.math.BigDecimal toBigDecimalOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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
