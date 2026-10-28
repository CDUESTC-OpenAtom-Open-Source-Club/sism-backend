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
 * Validates the "plan indicators must sum to 100" rule for a plan + target organization.
 *
 * <p>2026-10-28 口径调整（用户定案）：类名沿用历史命名，但实际校验范围已由
 * 「基础性任务下的指标」改为「**计划内全部指标**」——
 * 战略链（STRAT_TO_FUNC）取计划内全部任务的父指标；
 * 学院链（FUNC_TO_COLLEGE）取计划内全部任务（本部门创建、目标为该学院）的子指标。
 * 两条链的口径必须与导入时的自动分配（BusinessImportApplicationService#normalizePlanWeights）
 * 完全一致，否则会出现「分配完就被校验拦下」的死锁。
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
        validatePlanBasicWeight(planId, targetOrgId, excludedIndicatorIds, java.util.Map.of());
    }

    /**
     * 2026-10-23（异动状态更新对齐上报流程）：异动发起不再原地落库，修改条目的
     * 拟变更权重通过 weightOverrides 参与合计——校验口径 = 本批变更生效后的快照，
     * 而不是表里的审批前旧值。override 中不存在的指标照常取表值。
     */
    public void validatePlanBasicWeight(Long planId, Long targetOrgId, Set<Long> excludedIndicatorIds,
                                        java.util.Map<Long, BigDecimal> weightOverrides) {
        if (planId == null || targetOrgId == null) {
            return;
        }
        Set<Long> excluded = excludedIndicatorIds == null ? Set.of() : excludedIndicatorIds;
        java.util.Map<Long, BigDecimal> overrides =
                weightOverrides == null ? java.util.Map.of() : weightOverrides;

        java.util.Optional<Plan> plan = planRepository.findById(planId);
        if (plan.map(Plan::getPlanLevel).filter(PlanLevel.FUNC_TO_COLLEGE::equals).isPresent()) {
            validateFuncToCollegeIndicators(plan.get(), targetOrgId, excluded, overrides);
            return;
        }

        Set<Long> taskIds = jdbcTemplate.queryForList("""
                        SELECT t.task_id
                        FROM public.sys_task t
                        WHERE t.plan_id = ?
                          AND COALESCE(t.is_deleted, false) = false
                        """,
                Long.class,
                planId
        ).stream().collect(Collectors.toSet());

        if (taskIds.isEmpty()) {
            throw new IllegalStateException("当前计划不存在任务，不能下发");
        }

        // 2026-10-28 口径调整（用户定案）：权重按「整个计划合计 100」校验，
        // 不再只看基础性任务——此前发展性任务权重不参与校验，导致同一任务内
        // 权重合计可以随意写成 110、120 而无人拦截（实测可一路下发/异动/填报）。
        List<Indicator> planIndicators = indicatorRepository.findByTaskIds(List.copyOf(taskIds)).stream()
                .filter(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()))
                .filter(indicator -> !excluded.contains(indicator.getId()))
                .filter(indicator -> indicator.getParentIndicatorId() == null)
                .filter(indicator -> indicator.getTargetOrg() != null)
                .filter(indicator -> targetOrgId.equals(indicator.getTargetOrg().getId()))
                .collect(Collectors.toList());

        WeightSum weightSum = sumEffectiveWeights(planIndicators, overrides);
        BigDecimal totalWeight = weightSum.total();

        // 2026-10-28：存在「待分配」项（导入时留空落的 0.01 标记）时必须直接拒绝。
        // 待分配项不计入合计，若只看合计可能因为「其余项刚好 100」而被误放行，
        // 而库内实际合计并非 100 —— 这违背「权重加起来只能是 100」这条硬口径。
        if (weightSum.pendingCount() > 0) {
            throw new IllegalStateException(
                    "计划内指标权重合计必须为 100%，当前有 " + weightSum.pendingCount()
                            + " 项权重未分配（导入时留空）。"
                            + "请在指标列表中调整权重，或导入时留空由系统自动等分"
            );
        }

        if (totalWeight.compareTo(REQUIRED_TOTAL_WEIGHT) != 0) {
            BigDecimal diff = REQUIRED_TOTAL_WEIGHT.subtract(totalWeight);
            String direction = diff.signum() > 0 ? "还差 " : "超出 ";
            throw new IllegalStateException(
                    "计划内指标权重合计必须为 100%，当前为 "
                            + totalWeight.stripTrailingZeros().toPlainString() + "%（"
                            + direction + diff.abs().stripTrailingZeros().toPlainString()
                            + "%，共 " + planIndicators.size() + " 项指标）。"
                            + "请在指标列表中调整权重，或导入时留空由系统自动等分"
            );
        }
    }

    /** 权重合计结果：有效合计与「待分配」条数。 */
    private record WeightSum(BigDecimal total, long pendingCount) {
    }

    /**
     * 汇总参与合计的权重。
     *
     * <p>2026-10-28：导入时留空的权重会落「待分配」标记 0.01，它代表还没分配，
     * 不应计入合计 —— 否则报错数值失真（100.01% 看起来像超额 0.01%，而不是"还有 1 项没分配"）。
     */
    private WeightSum sumEffectiveWeights(List<Indicator> indicators, java.util.Map<Long, BigDecimal> overrides) {
        List<BigDecimal> weights = indicators.stream()
                .map(indicator -> resolveEffectiveWeight(indicator, overrides))
                .filter(weight -> weight != null)
                .collect(Collectors.toList());
        BigDecimal total = weights.stream()
                .filter(weight -> weight.compareTo(PENDING_WEIGHT) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long pending = weights.stream()
                .filter(weight -> weight.compareTo(PENDING_WEIGHT) <= 0)
                .count();
        return new WeightSum(total, pending);
    }

    /** 待分配标记：与导入侧 BusinessImportApplicationService#PENDING_WEIGHT 保持一致。 */
    private static final BigDecimal PENDING_WEIGHT = new BigDecimal("0.01");

    /** 拟变更权重优先（异动快照口径），未覆盖的取表值。 */
    private BigDecimal resolveEffectiveWeight(Indicator indicator, java.util.Map<Long, BigDecimal> overrides) {
        BigDecimal overridden = overrides.get(indicator.getId());
        return overridden != null ? overridden : indicator.getWeightPercent();
    }

    private void validateFuncToCollegeIndicators(Long planId, Long targetOrgId, Set<Long> excluded) {
        validateFuncToCollegeIndicators(planId, targetOrgId, excluded, java.util.Map.of());
    }

    private void validateFuncToCollegeIndicators(Long planId, Long targetOrgId, Set<Long> excluded,
                                                 java.util.Map<Long, BigDecimal> weightOverrides) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        validateFuncToCollegeIndicators(plan, targetOrgId, excluded, weightOverrides);
    }

    private void validateFuncToCollegeIndicators(Plan plan, Long targetOrgId, Set<Long> excluded,
                                                 java.util.Map<Long, BigDecimal> weightOverrides) {
        if (plan.getPlanLevel() != PlanLevel.FUNC_TO_COLLEGE) {
            throw new IllegalStateException("当前计划不是下发到学院的计划，不能下发");
        }

        // 2026-10-28 口径调整（用户定案）：与战略链一致，权重按「整个计划合计 100」校验，
        // 不再只取基础性任务的子指标——否则「基础性子集合计 100、计划内全部子指标合计 100」
        // 两套口径并存，导入时的自动分配与下发校验会互相打架。
        Set<Long> taskIds = jdbcTemplate.queryForList("""
                        SELECT t.task_id
                        FROM public.sys_task t
                        WHERE t.plan_id = ?
                          AND COALESCE(t.is_deleted, false) = false
                        """,
                Long.class,
                plan.getId()
        ).stream().collect(Collectors.toSet());

        if (taskIds.isEmpty()) {
            throw new IllegalStateException("当前计划不存在任务，不能下发");
        }

        List<Indicator> planIndicators = indicatorRepository.findByTaskIds(List.copyOf(taskIds)).stream()
                .filter(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()))
                .filter(indicator -> !excluded.contains(indicator.getId()))
                .filter(indicator -> indicator.getParentIndicatorId() != null)
                .filter(indicator -> indicator.getOwnerOrg() != null)
                .filter(indicator -> plan.getCreatedByOrgId().equals(indicator.getOwnerOrg().getId()))
                .filter(indicator -> indicator.getTargetOrg() != null)
                .filter(indicator -> targetOrgId.equals(indicator.getTargetOrg().getId()))
                .collect(Collectors.toList());

        WeightSum weightSum = sumEffectiveWeights(planIndicators, weightOverrides);
        BigDecimal totalWeight = weightSum.total();

        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalStateException("当前计划不存在学院指标，不能下发");
        }

        // 与战略链一致：存在待分配项时直接拒绝（否则合计可能刚好 100 而被误放行）
        if (weightSum.pendingCount() > 0) {
            throw new IllegalStateException(
                    "计划内指标权重合计必须为 100%，当前有 " + weightSum.pendingCount()
                            + " 项权重未分配（导入时留空）。"
                            + "请在指标列表中调整权重，或导入时留空由系统自动等分"
            );
        }

        if (totalWeight.compareTo(REQUIRED_TOTAL_WEIGHT) != 0) {
            BigDecimal diff = REQUIRED_TOTAL_WEIGHT.subtract(totalWeight);
            String direction = diff.signum() > 0 ? "还差 " : "超出 ";
            throw new IllegalStateException(
                    "计划内指标权重合计必须为 100%，当前为 "
                            + totalWeight.stripTrailingZeros().toPlainString() + "%（"
                            + direction + diff.abs().stripTrailingZeros().toPlainString()
                            + "%，共 " + planIndicators.size() + " 项指标）。"
                            + "请在指标列表中调整权重，或导入时留空由系统自动等分"
            );
        }
    }
}
