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
     * P5 指标异动：异动审批终态后清除指标锁标记（通过/驳回都清除，
     * 驳回时变更内容保留在原表，由发起人自行还原）。
     */
    @Override
    public void endIndicatorMutation(String entityType, Long entityId, boolean approved) {
        if (!"INDICATOR".equalsIgnoreCase(entityType) || entityId == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE public.indicator SET mutation_status = NULL, mutation_started_at = NULL WHERE id = ?",
                entityId);
    }

    // 2026-10-20 用户定案：异动期间不再锁死填报/提交。
    // 原 isOrgLockedByMutation 实现已移除，接口 default 返回 false（不锁）。
}
