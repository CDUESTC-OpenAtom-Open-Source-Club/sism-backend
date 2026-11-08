package com.sism.strategy.infrastructure;

import com.sism.organization.domain.OrganizationRepository;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.strategy.domain.cycle.Cycle;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanDisplayName;
import com.sism.strategy.domain.repository.CycleRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class StrategyWorkflowBusinessContextAdapter implements WorkflowBusinessContextPort {

    private static final String PLAN_ENTITY_TYPE = "PLAN";
    private static final String PLAN_REPORT_ENTITY_TYPE = "PLAN_REPORT";

    private final PlanRepository planRepository;
    private final OrganizationRepository organizationRepository;
    private final CycleRepository cycleRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Override
    public Optional<Long> getPlanIdByEntity(String entityType, Long entityId) {
        if (!PLAN_ENTITY_TYPE.equalsIgnoreCase(entityType) || entityId == null) {
            return Optional.empty();
        }
        return Optional.of(entityId);
    }

    /**
     * 2026-11-08（学院指标单层口径）：该业务实体对应的「归口职能部门」。
     *
     * <p><b>为什么必须同时支持 PLAN 和 PLAN_REPORT</b>：学院上报链的审批实例
     * entityType 是 {@code PLAN_REPORT}、entityId 是 reportId（见 ReportWorkflowEventListener）；
     * 只认 PLAN 会让真实链路上永远读不到归口部门，席位继续回退到战略部（35），
     * 而战略部没有 role2 账号 → 第 3 环直接抛 No available approver candidates。
     * 这是本方法第一版的真实缺陷，必须先把报告映射回计划再查。
     *
     * <p>未配置归口部门（存量数据）→ empty，调用方回退 created_by_org_id 原口径。
     */
    @Override
    public Optional<Long> findFunctionalOrgIdByEntity(String entityType, Long entityId) {
        Long planId = resolvePlanIdForContext(entityType, entityId);
        if (planId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT functional_org_id FROM public.plan WHERE id = ? AND functional_org_id IS NOT NULL",
                (rs, rowNum) -> rs.getLong("functional_org_id"),
                planId).stream().findFirst();
    }

    /**
     * 把审批实例的业务实体映射到计划 id。
     *
     * <p>PLAN → 自身；PLAN_REPORT → 报告的 plan_id；其它 → 无法映射（异动链用指标，
     * 不需要归口部门，走默认 empty）。
     */
    private Long resolvePlanIdForContext(String entityType, Long entityId) {
        if (entityId == null) {
            return null;
        }
        if ("PLAN".equalsIgnoreCase(entityType)) {
            return entityId;
        }
        if ("PLAN_REPORT".equalsIgnoreCase(entityType)) {
            return jdbcTemplate.query(
                    "SELECT plan_id FROM public.plan_report WHERE id = ?",
                    (rs, rowNum) -> rs.getLong("plan_id"),
                    entityId).stream().findFirst().orElse(null);
        }
        return null;
    }

    @Override
    public Optional<BusinessSummary> getBusinessSummary(String entityType, Long entityId) {
        // 2026-10-21：战略任务改名异动（entityType=TASK）——卡片显示任务名与流向
        if ("TASK".equalsIgnoreCase(entityType) && entityId != null) {
            return resolveTaskSummary(entityId);
        }
        // 2026-10-10：指标异动链（entityType=INDICATOR）补齐部门信息——
        // 流转方向 = 发起方（指标归属组织）-> 目标方（指标 target_org），
        // 此前该类型返回 empty，前端卡片只能显示「目标部门」占位词。
        if ("INDICATOR".equalsIgnoreCase(entityType) && entityId != null) {
            // 2026-10-31 缺陷修复（取消异动软删除新增行后审批历史丢业务标题）：
            // 先按原口径查活行；查不到（is_deleted=true：取消异动的新增行、终拒/通过删除
            // 的归档行）再走兜底查询——去掉 is_deleted 过滤，标题优先取 audit_log 最新
            // 快照的 after 值（软删除只翻标记不改业务值，快照与行值一致，双重保险），
            // 保证审批中心「审批历史」的「已取消」卡仍能展示当时新增的内容。
            // 调用方波及面已核实：ApproverResolver 的摘要解析仅限 PLAN/PLAN_REPORT，
            // 不会走到 INDICATOR 分支；两个读模型消费方（审批中心卡片）正是要这个兜底。
            Optional<BusinessSummary> live = jdbcTemplate.query(
                    "SELECT owner_org_id, target_org_id, indicator_desc FROM public.indicator WHERE id = ? AND COALESCE(is_deleted, false) = false",
                    indicatorSummaryExtractor(),
                    entityId);
            if (live.isPresent()) {
                return live;
            }
            return jdbcTemplate.query(
                    """
                    SELECT i.owner_org_id,
                           i.target_org_id,
                           COALESCE(NULLIF(snapshot.after_desc, ''), i.indicator_desc) AS indicator_desc
                    FROM public.indicator i
                    LEFT JOIN LATERAL (
                        SELECT al.after_json ->> 'indicator_desc' AS after_desc
                        FROM public.audit_log al
                        WHERE al.entity_type = 'INDICATOR' AND al.entity_id = i.id
                        ORDER BY al.created_at DESC, al.log_id DESC
                        LIMIT 1
                    ) snapshot ON true
                    WHERE i.id = ?
                    """,
                    indicatorSummaryExtractor(),
                    entityId);
        }
        // 2026-10-31 P-18 修复：上报审批（entityType=PLAN_REPORT）此前在本适配器返回 empty，
        // 审批中心卡片拿不到 planName 只能回退消息标题。参照 TASK 分支的跨域原生 SQL 取数
        // （public.plan_report 有 report_month / report_org_id / plan_id，无需新建跨模块接口），
        // 摘要口径（用户定案）「{年份}年度 {月份}月上报（{上报组织} -> {接收部门}）」：
        // 上报方向自下而上——上报组织=report_org_id，接收部门=计划创建方（下发方）；
        // 拿不到的字段逐级降级（轮次缺→省年度月段；单侧部门名缺→只写另一侧；全缺→仅「上报」）。
        if (PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType) && entityId != null) {
            return resolvePlanReportSummary(entityId);
        }
        if (!PLAN_ENTITY_TYPE.equalsIgnoreCase(entityType) || entityId == null) {
            return Optional.empty();
        }
        return planRepository.findById(entityId).map(this::toSummary);
    }

    /** 指标行 → 业务摘要（活行查询与 audit_log 快照兜底查询共用同一映射）。 */
    private org.springframework.jdbc.core.ResultSetExtractor<Optional<BusinessSummary>> indicatorSummaryExtractor() {
        return rs -> {
            if (!rs.next()) {
                return Optional.<BusinessSummary>empty();
            }
            long ownerOrgId = rs.getLong("owner_org_id");
            long targetOrgId = rs.getLong("target_org_id");
            String desc = rs.getString("indicator_desc");
            String displayName = desc != null && desc.length() > 40 ? desc.substring(0, 40) : desc;
            Long sourceId = ownerOrgId > 0 ? ownerOrgId : null;
            Long targetId = targetOrgId > 0 ? targetOrgId : null;
            return Optional.of(new BusinessSummary(
                    null,
                    displayName,
                    sourceId,
                    resolveOrgName(sourceId),
                    targetId,
                    resolveOrgName(targetId),
                    displayName
            ));
        };
    }

    /**
     * 2026-10-21：战略任务改名异动的业务上下文——任务名 + 战略部（创建方）→ 目标部门，
     * 使审批卡片显示「战略发展部 → 某部门 · 任务名」而不是占位词。
     */
    private Optional<BusinessSummary> resolveTaskSummary(Long taskId) {
        return jdbcTemplate.query(
                """
                SELECT t.name, t.org_id, t.created_by_org_id, t.plan_id
                FROM public.sys_task t
                WHERE t.task_id = ? AND COALESCE(t.is_deleted, false) = false
                """,
                rs -> {
                    if (!rs.next()) {
                        return Optional.<BusinessSummary>empty();
                    }
                    String name = rs.getString("name");
                    String displayName = name != null && name.length() > 40
                            ? name.substring(0, 40)
                            : name;
                    long orgId = rs.getLong("org_id");
                    long createdByOrgId = rs.getLong("created_by_org_id");
                    long planId = rs.getLong("plan_id");
                    Long sourceId = createdByOrgId > 0 ? createdByOrgId : null;
                    Long targetId = orgId > 0 ? orgId : null;
                    return Optional.of(new BusinessSummary(
                            planId > 0 ? planId : null,
                            displayName,
                            sourceId,
                            resolveOrgName(sourceId),
                            targetId,
                            resolveOrgName(targetId),
                            displayName
                    ));
                },
                taskId);
    }

    /**
     * 2026-10-31 P-18：上报审批的业务摘要——public.plan_report 行 + 所属计划。
     * 不过滤 is_deleted：被取消/归档的上报在「审批历史」里仍要能显示当时的轮次与流向
     * （与 INDICATOR 分支的 audit_log 兜底同一诉求，软删只翻标记不改业务值）。
     */
    private Optional<BusinessSummary> resolvePlanReportSummary(Long reportId) {
        return jdbcTemplate.query(
                "SELECT plan_id, report_month, report_org_id FROM public.plan_report WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return Optional.<BusinessSummary>empty();
                    }
                    long planId = rs.getLong("plan_id");
                    String reportMonth = rs.getString("report_month");
                    long reportOrgId = rs.getLong("report_org_id");
                    Long sourceId = reportOrgId > 0 ? reportOrgId : null;
                    String sourceOrgName = resolveOrgName(sourceId);
                    Long targetId = null;
                    String targetOrgName = null;
                    if (planId > 0) {
                        // 接收部门=计划创建方（下发方）；计划缺失时降级为只写上报组织
                        targetId = planRepository.findById(planId)
                                .map(Plan::getCreatedByOrgId)
                                .orElse(null);
                        targetOrgName = resolveOrgName(targetId);
                    }
                    String route;
                    if (sourceOrgName != null && targetOrgName != null) {
                        route = "（" + sourceOrgName + " -> " + targetOrgName + "）";
                    } else if (sourceOrgName != null) {
                        route = "（" + sourceOrgName + "）";
                    } else if (targetOrgName != null) {
                        route = "（" + targetOrgName + "）";
                    } else {
                        route = "";
                    }
                    Integer year = parseReportYear(reportMonth);
                    Integer month = parseReportMonthOfYear(reportMonth);
                    String displayName = (year != null && month != null
                            ? year + "年度 " + month + "月上报"
                            : "上报") + route;
                    return Optional.of(new BusinessSummary(
                            planId > 0 ? planId : null,
                            displayName,
                            sourceId,
                            sourceOrgName,
                            targetId,
                            targetOrgName,
                            displayName
                    ));
                },
                reportId);
    }

    /** report_month 为 yyyyMM 串（PlanReport.normalizeReportMonth 归一口径）；不可解析返回 null。 */
    private Integer parseReportYear(String reportMonth) {
        if (reportMonth == null || !reportMonth.trim().matches("\\d{6}")) {
            return null;
        }
        try {
            return Integer.valueOf(reportMonth.trim().substring(0, 4));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private Integer parseReportMonthOfYear(String reportMonth) {
        if (reportMonth == null || !reportMonth.trim().matches("\\d{6}")) {
            return null;
        }
        try {
            return Integer.valueOf(reportMonth.trim().substring(4, 6));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private BusinessSummary toSummary(Plan plan) {
        Long sourceOrgId = plan.getCreatedByOrgId();
        Long targetOrgId = plan.getTargetOrgId();
        String sourceOrgName = resolveOrgName(sourceOrgId);
        String targetOrgName = resolveOrgName(targetOrgId);
        // 2026-10-31 用户定案：业务名统一「{年份}年度 计划（源部门 -> 目标部门）」，
        // 不再输出 "Plan N" 占位（口径与前端 buildPlanReferenceLabel 逐字符一致；
        // 源部门=创建方，年份来自计划所属考核周期 cycle）
        String displayName = PlanDisplayName.planReferenceLabel(
                resolveCycleYear(plan.getCycleId()), sourceOrgName, targetOrgName);
        return new BusinessSummary(
                plan.getId(),
                displayName,
                sourceOrgId,
                sourceOrgName,
                targetOrgId,
                targetOrgName,
                displayName
        );
    }

    /** 计划年份取自所属考核周期；周期缺失/异常时不写年度段，绝不回退 "Plan N"。 */
    private Integer resolveCycleYear(Long cycleId) {
        if (cycleId == null) {
            return null;
        }
        try {
            return cycleRepository.findById(cycleId).map(Cycle::getYear).orElse(null);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private String resolveOrgName(Long orgId) {
        if (orgId == null) {
            return null;
        }
        return organizationRepository.findById(orgId)
                .map(org -> org.getName() != null && !org.getName().isBlank() ? org.getName() : "Org#" + orgId)
                .orElse("Org#" + orgId);
    }

    /**
     * P5 指标异动：异动审批到达终态后清除指标锁标记。
     *
     * 2026-10-21：按异动类型收尾（DELETE 通过才归档、CREATE 驳回要归档）的完整逻辑
     * 统一放在 StrategyWorkflowBusinessStatusAdapter.syncBusinessStatus ——
     * 那里是 instances/{id}/approve|reject 的实际同步点，能同时拿到终态与实体，
     * 且不会被 approveTask / rejectTask 两条链路重复触发。此处只保留解锁语义，
     * 避免同一实体被两个适配器各写一次导致 CREATE 驳回被重复归档。
     */
    @Override
    public void endIndicatorMutation(String entityType, Long entityId, boolean approved) {
        if (entityId == null) {
            return;
        }
        if ("TASK".equalsIgnoreCase(entityType)) {
            jdbcTemplate.update(
                    "UPDATE public.sys_task SET mutation_status = NULL, mutation_started_at = NULL WHERE task_id = ?",
                    entityId);
            return;
        }
        if (!"INDICATOR".equalsIgnoreCase(entityType)) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE public.indicator SET mutation_status = NULL, mutation_started_at = NULL WHERE id = ?",
                entityId);
    }

    // 2026-10-20 用户定案：异动期间不再锁死填报/提交。
    // 原 isOrgLockedByMutation 实现已移除，接口 default 返回 false（不锁）。
}
