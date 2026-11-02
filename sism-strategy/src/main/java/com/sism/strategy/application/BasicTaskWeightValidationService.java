package com.sism.strategy.application;

import com.sism.shared.domain.indicator.IndicatorGateMessages;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
                .map(indicator -> resolveEffectiveWeight(indicator, overrides))
                .filter(weight -> weight != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalWeight.compareTo(REQUIRED_TOTAL_WEIGHT) != 0) {
            throw new IllegalStateException(
                    "基础性任务指标权重合计必须为100，当前为" + totalWeight.stripTrailingZeros().toPlainString()
            );
        }
    }

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
            // 2026-10-31 用户拍板：被拦文案点名「无法计入基础性权重合计」的父级核心指标
            String parentHint = describeNonBasicParentIndicators(plan, targetOrgId, excluded, basicTaskIds);
            if (parentHint != null) {
                throw new IllegalStateException(parentHint);
            }
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
                .map(indicator -> resolveEffectiveWeight(indicator, weightOverrides))
                .filter(weight -> weight != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) {
            // 2026-10-31 用户拍板：被拦文案点名「无法计入基础性权重合计」的父级核心指标
            String parentHint = describeNonBasicParentIndicators(plan, targetOrgId, excluded, basicTaskIds);
            if (parentHint != null) {
                throw new IllegalStateException(parentHint);
            }
            throw new IllegalStateException("当前计划不存在学院指标，不能下发");
        }

        if (totalWeight.compareTo(REQUIRED_TOTAL_WEIGHT) != 0) {
            // 2026-10-31 用户拍板：被拦文案点名「无法计入基础性权重合计」的父级核心指标
            String parentHint = describeNonBasicParentIndicators(plan, targetOrgId, excluded, basicTaskIds);
            if (parentHint != null) {
                throw new IllegalStateException(parentHint);
            }
            throw new IllegalStateException(
                    "学院指标权重合计必须为100，当前为" + totalWeight.stripTrailingZeros().toPlainString()
            );
        }
    }

    /**
     * 2026-10-31 用户拍板（学院导入下发被拦点名父级）：找出「所属任务非基础性」的父级核心指标——
     * 这些父级下的学院子指标不在基础性权重合计范围内，是「不存在基础性任务/权重不满足」被拦的根因。
     * 文案：父级核心指标『A』『B』 等 N 项所属任务为“发展性”，无法计入基础性权重合计；
     * 基础性指标权重全计划合计须为 100（点名风格跟随 IndicatorGateMessages：只报名称、
     * 超 20 字截断、前 3 个 + 等 N 项）。找不到此类父级时返回 null，调用方回退原有文案。
     */
    private String describeNonBasicParentIndicators(Plan plan,
                                                    Long targetOrgId,
                                                    Set<Long> excluded,
                                                    Set<Long> basicTaskIds) {
        // 该学院计划下全部任务（含非基础性）——用于圈定落位在这些任务上的学院子指标
        List<java.util.Map<String, Object>> planTaskRows = jdbcTemplate.queryForList(
                """
                SELECT t.task_id, t.task_type
                FROM public.sys_task t
                WHERE t.plan_id = ?
                  AND COALESCE(t.is_deleted, false) = false
                """,
                plan.getId());
        List<Long> planTaskIds = planTaskRows.stream()
                .map(row -> ((Number) row.get("task_id")).longValue())
                .toList();
        if (planTaskIds.isEmpty()) {
            return null;
        }
        List<Indicator> scopedChildren = indicatorRepository.findByTaskIds(planTaskIds).stream()
                .filter(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()))
                .filter(indicator -> !excluded.contains(indicator.getId()))
                .filter(indicator -> indicator.getParentIndicatorId() != null)
                .filter(indicator -> indicator.getOwnerOrg() != null
                        && plan.getCreatedByOrgId() != null
                        && plan.getCreatedByOrgId().equals(indicator.getOwnerOrg().getId()))
                .filter(indicator -> indicator.getTargetOrg() != null
                        && targetOrgId.equals(indicator.getTargetOrg().getId()))
                .toList();
        // 落位在非基础性任务上的子指标：其父级核心指标无法计入基础性权重合计
        List<Long> nonBasicParentIds = scopedChildren.stream()
                .filter(indicator -> indicator.getTaskId() == null
                        || !basicTaskIds.contains(indicator.getTaskId()))
                .map(Indicator::getParentIndicatorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (nonBasicParentIds.isEmpty()) {
            return null;
        }
        java.util.Map<Long, Indicator> parentById = indicatorRepository.findByIds(nonBasicParentIds).stream()
                .filter(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()))
                .collect(Collectors.toMap(Indicator::getId, Function.identity(), (left, right) -> left));
        List<Indicator> parents = nonBasicParentIds.stream()
                .map(parentById::get)
                .filter(Objects::nonNull)
                .toList();
        if (parents.isEmpty()) {
            return null;
        }
        // 非基础性任务的类型只有 DEVELOPMENT（TaskType 仅 BASIC/DEVELOPMENT 两种），
        // 任务类别缺失时导入链同样按「发展性」兜底，故文案固定为「发展性」
        String preview = parents.stream()
                .limit(IndicatorGateMessages.NAME_PREVIEW_LIMIT)
                .map(indicator -> "『" + IndicatorGateMessages.truncateIndicatorName(indicator.getIndicatorDesc())
                        + "』")
                .collect(Collectors.joining());
        if (parents.size() > IndicatorGateMessages.NAME_PREVIEW_LIMIT) {
            preview += " 等 " + parents.size() + " 项";
        }
        return "父级核心指标" + preview + "所属任务为“发展性”，无法计入基础性权重合计；基础性指标权重全计划合计须为 100";
    }
}
