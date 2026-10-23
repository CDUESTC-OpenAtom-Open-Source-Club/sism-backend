package com.sism.strategy.application;

import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates the "basic task indicators must sum to 100" rule for a plan + target organization.
 */
@Service
@RequiredArgsConstructor
public class BasicTaskWeightValidationService {

    private static final BigDecimal REQUIRED_TOTAL_WEIGHT = BigDecimal.valueOf(100);

    private final JdbcTemplate jdbcTemplate;
    private final IndicatorRepository indicatorRepository;
    private final PlanRepository planRepository;

    public void validatePlanBasicWeight(Long planId, Long targetOrgId) {
        validatePlanBasicWeight(planId, targetOrgId, Set.of());
    }

    /**
     * 2026-10-22：批量异动里「删一条 + 加一条」时，被删指标在审批通过前仍留在表里
     * （删除要等审批通过才归档），若把它计入合计就会出现 100+10=110 的假超标。
     * 因此校验时把同批次待删除的指标显式排除，等价于「这批异动生效后的权重快照」。
     */
    public void validatePlanBasicWeight(Long planId, Long targetOrgId, Set<Long> excludedIndicatorIds) {
        if (planId == null || targetOrgId == null) {
            return;
        }
        Set<Long> excluded = excludedIndicatorIds == null ? Set.of() : excludedIndicatorIds;

        java.util.Optional<Plan> plan = planRepository.findById(planId);
        if (plan.map(Plan::getPlanLevel).filter(PlanLevel.FUNC_TO_COLLEGE::equals).isPresent()) {
            validateFuncToCollegeIndicators(plan.get(), targetOrgId, excluded);
            return;
        }

        Set<Long> basicTaskIds = jdbcTemplate.queryForList("""
                        SELECT t.task_id
                        FROM public.sys_task t
                        WHERE t.plan_id = ?
                          AND COALESCE(t.is_deleted, false) = false
                          AND t.task_type = 'BASIC'
                        """,
                Long.class,
                planId
        ).stream().collect(Collectors.toSet());

        if (basicTaskIds.isEmpty()) {
            throw new IllegalStateException("当前计划不存在基础性任务，不能下发");
        }

        BigDecimal totalWeight = indicatorRepository.findByTaskIds(List.copyOf(basicTaskIds)).stream()
                .filter(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()))
                .filter(indicator -> !excluded.contains(indicator.getId()))
                .filter(indicator -> indicator.getParentIndicatorId() == null)
                .filter(indicator -> indicator.getTargetOrg() != null)
                .filter(indicator -> targetOrgId.equals(indicator.getTargetOrg().getId()))
                .map(Indicator::getWeightPercent)
                .filter(weight -> weight != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalWeight.compareTo(REQUIRED_TOTAL_WEIGHT) != 0) {
            throw new IllegalStateException(
                    "基础性任务指标权重合计必须为100，当前为" + totalWeight.stripTrailingZeros().toPlainString()
            );
        }
    }

    private void validateFuncToCollegeIndicators(Long planId, Long targetOrgId, Set<Long> excluded) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        validateFuncToCollegeIndicators(plan, targetOrgId, excluded);
    }

    private void validateFuncToCollegeIndicators(Plan plan, Long targetOrgId, Set<Long> excluded) {
        if (plan.getPlanLevel() != PlanLevel.FUNC_TO_COLLEGE) {
            throw new IllegalStateException("当前计划不存在基础性任务，不能下发");
        }

        Set<Long> basicTaskIds = jdbcTemplate.queryForList("""
                        SELECT t.task_id
                        FROM public.sys_task t
                        WHERE t.plan_id = ?
                          AND COALESCE(t.is_deleted, false) = false
                          AND t.task_type = 'BASIC'
                        """,
                Long.class,
                plan.getId()
        ).stream().collect(Collectors.toSet());

        if (basicTaskIds.isEmpty()) {
            throw new IllegalStateException("当前计划不存在基础性任务，不能下发");
        }

        BigDecimal totalWeight = indicatorRepository.findByTaskIds(List.copyOf(basicTaskIds)).stream()
                .filter(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()))
                .filter(indicator -> !excluded.contains(indicator.getId()))
                .filter(indicator -> indicator.getParentIndicatorId() != null)
                .filter(indicator -> indicator.getOwnerOrg() != null)
                .filter(indicator -> plan.getCreatedByOrgId().equals(indicator.getOwnerOrg().getId()))
                .filter(indicator -> indicator.getTargetOrg() != null)
                .filter(indicator -> targetOrgId.equals(indicator.getTargetOrg().getId()))
                .map(Indicator::getWeightPercent)
                .filter(weight -> weight != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalStateException("当前计划不存在学院指标，不能下发");
        }

        if (totalWeight.compareTo(REQUIRED_TOTAL_WEIGHT) != 0) {
            throw new IllegalStateException(
                    "学院指标权重合计必须为100，当前为" + totalWeight.stripTrailingZeros().toPlainString()
            );
        }
    }
}
