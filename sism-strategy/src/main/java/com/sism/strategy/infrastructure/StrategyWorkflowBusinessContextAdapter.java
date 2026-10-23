package com.sism.strategy.infrastructure;

import com.sism.organization.domain.OrganizationRepository;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class StrategyWorkflowBusinessContextAdapter implements WorkflowBusinessContextPort {

    private static final String PLAN_ENTITY_TYPE = "PLAN";

    private final PlanRepository planRepository;
    private final OrganizationRepository organizationRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Override
    public Optional<Long> getPlanIdByEntity(String entityType, Long entityId) {
        if (!PLAN_ENTITY_TYPE.equalsIgnoreCase(entityType) || entityId == null) {
            return Optional.empty();
        }
        return Optional.of(entityId);
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
            return jdbcTemplate.query(
                    "SELECT owner_org_id, target_org_id, indicator_desc FROM public.indicator WHERE id = ? AND COALESCE(is_deleted, false) = false",
                    rs -> {
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
                    },
                    entityId);
        }
        if (!PLAN_ENTITY_TYPE.equalsIgnoreCase(entityType) || entityId == null) {
            return Optional.empty();
        }
        return planRepository.findById(entityId).map(this::toSummary);
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

    private BusinessSummary toSummary(Plan plan) {
        Long sourceOrgId = plan.getCreatedByOrgId();
        Long targetOrgId = plan.getTargetOrgId();
        return new BusinessSummary(
                plan.getId(),
                "Plan " + plan.getId(),
                sourceOrgId,
                resolveOrgName(sourceOrgId),
                targetOrgId,
                resolveOrgName(targetOrgId),
                "Plan " + plan.getId()
        );
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
