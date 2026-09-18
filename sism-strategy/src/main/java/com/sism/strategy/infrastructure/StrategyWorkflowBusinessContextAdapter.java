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

    /**
     * P5 指标异动：组织下存在异动中的指标时全面锁死填报/提交。
     */
    @Override
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
}
