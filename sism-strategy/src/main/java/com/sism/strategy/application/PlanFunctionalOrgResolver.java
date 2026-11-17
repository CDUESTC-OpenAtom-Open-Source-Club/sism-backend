package com.sism.strategy.application;

import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学院计划「归口职能部门」解析器 —— 2026-11-13 用户拍板（学院上报按归口部门分批提交）。
 *
 * <p><b>唯一权威口径</b>：计划下学院子指标（该计划任务下、parent_indicator_id 非空、未删除）
 * → 父级核心指标.target_org_id 全集；多部门取 id 最小（MIN）作为计划级归口。
 * 学院子指标由战略发展部统一创建且父级反查 100% 可靠（DB 实测 0 条孤儿），方案A：indicator 不动。
 *
 * <p><b>共用点（避免口径漂移）</b>：
 * <ul>
 *   <li>Flyway V104 存量回填（同款子查询的 SQL 版）；</li>
 *   <li>导入链 {@code BusinessImportApplicationService#applyFunctionalOrgFromDispatchChain}；</li>
 *   <li>计划提交补写 {@code PlanApplicationService#submitPlanForApprovalInTransaction}
 *       （「新增行+发起审批」路径此前不写 functional_org_id，提交下发前在此补齐）。</li>
 * </ul>
 *
 * <p>所有 SQL 均参数绑定，无字符串拼接。
 *
 * <p><b>2026-11-17（学院线粒度改造）后的职责边界</b>：归口部门（functional_org_id）现在是
 * 「线身份」的一部分，运行时不可覆盖写。因此本类只承担两件事：
 * <ul>
 *   <li><b>存量回填</b>：{@link #chooseFunctionalOrgId(Long, Set)} / {@link #backfillFunctionalOrgIfAbsent(Plan)}
 *       —— 只对「归口为空」的存量行补值（与 V104 同口径，多部门取 MIN 并告警）；</li>
 *   <li><b>异常告警</b>：{@link #resolveUniqueFunctionalOrgId(Long, Set)} —— 运行时若发现多归口，
 *       记 error 并返回 null（不猜），把「口径分裂」暴露给运维而不是静默串线。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanFunctionalOrgResolver {

    /** 与 V104 迁移回填子查询逐字同口径（不含外层 UPDATE 条件）。 */
    private static final String SQL_FUNCTIONAL_ORG_IDS = """
            SELECT DISTINCT parent.target_org_id
            FROM public.sys_task t
            JOIN public.indicator child ON child.task_id = t.task_id
                 AND child.parent_indicator_id IS NOT NULL
                 AND COALESCE(child.is_deleted, false) = false
            JOIN public.indicator parent ON parent.id = child.parent_indicator_id
            WHERE t.plan_id = ?
              AND parent.target_org_id IS NOT NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PlanRepository planRepository;

    /**
     * DB 反查：计划下学院子指标的父级核心指标 target_org_id 全集（去重）。
     */
    public Set<Long> resolveFunctionalOrgIds(Long planId) {
        if (planId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.queryForList(SQL_FUNCTIONAL_ORG_IDS, Long.class, planId));
    }

    /**
     * 归口选择（<b>存量回填口径</b>）：候选集合为空时回退 DB 反查（与 V104 同口径）；
     * 多部门取 id 最小（MIN）并 warn —— 一个学院理论上只归口一个职能部门，多部门是口径分裂信号，
     * 打 warn 便于运维发现。
     *
     * <p><b>2026-11-17（学院线粒度改造）降级说明</b>：本方法退化为<b>存量回填专用</b>，
     * 不再参与运行时覆盖写——归口部门现在是「线身份」的一部分，运行时改写它等于把 A 线的
     * 指标搬到 B 线的计划下。运行时需要「唯一归口」的场景请用
     * {@link #resolveUniqueFunctionalOrgId(Long, Set)}（多值不猜，直接告警）。
     *
     * @param candidates 已收集的候选部门集合（如导入批内的父级目标部门）；null/空 → DB 反查
     * @return 唯一归口（MIN）；无法确定时 null
     */
    public Long chooseFunctionalOrgId(Long planId, Set<Long> candidates) {
        Set<Long> effective = (candidates == null || candidates.isEmpty())
                ? resolveFunctionalOrgIds(planId)
                : candidates;
        Long chosen = effective.stream()
                .filter(Objects::nonNull)
                .min(Long::compareTo)
                .orElse(null);
        if (chosen == null) {
            return null;
        }
        if (effective.size() > 1) {
            log.warn("学院计划 {} 的父级核心指标来自多个职能部门 {}，归口部门取 id 最小者 {}；"
                    + "学院上报审批将派给该部门，请确认数据口径", planId, effective, chosen);
        }
        return chosen;
    }

    /**
     * 2026-11-17（学院线粒度改造）：运行时口径的「唯一归口」解析——<b>多值不猜</b>。
     *
     * <p>与 {@link #chooseFunctionalOrgId(Long, Set)} 的区别：这里遇到多个归口时
     * 记 error 级告警并返回 null（不静默取 MIN），因为运行时取 MIN 会把某条线的身份
     * 定成另一个部门的线。
     *
     * @param candidates 已收集的候选部门集合；null/空 → DB 反查
     * @return 唯一归口；无法唯一确定时 null
     */
    public Long resolveUniqueFunctionalOrgId(Long planId, Set<Long> candidates) {
        Set<Long> effective = (candidates == null || candidates.isEmpty())
                ? resolveFunctionalOrgIds(planId)
                : candidates;
        Set<Long> distinct = effective.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) {
            return null;
        }
        if (distinct.size() > 1) {
            log.error("学院计划 {} 的父级核心指标来自多个职能部门 {}，无法唯一确定归口部门；"
                    + "请按归口部门拆分数据后再处理（不自动取 MIN，避免串线）", planId, distinct);
            return null;
        }
        return distinct.iterator().next();
    }

    /**
     * 2026-11-13：FUNC_TO_COLLEGE 计划 functional_org_id 为空时按父级反查落值。
     * 供计划提交下发审批前调用（调用方处于提交事务内，本方法以 REQUIRED 传播加入同一事务）；
     * 仅补空值，不覆盖已落值（导入链负责按批次刷新）。
     *
     * @return true=本次补写了归口
     */
    @Transactional
    public boolean backfillFunctionalOrgIfAbsent(Plan plan) {
        if (plan == null || plan.getId() == null || plan.getFunctionalOrgId() != null) {
            return false;
        }
        if (plan.getPlanLevel() != PlanLevel.FUNC_TO_COLLEGE) {
            return false;
        }
        Long chosen = chooseFunctionalOrgId(plan.getId(), null);
        if (chosen == null) {
            return false;
        }
        plan.setFunctionalOrgId(chosen);
        planRepository.save(plan);
        log.info("学院计划 {} 提交下发前按父级核心指标回填归口职能部门 functionalOrgId={}",
                plan.getId(), chosen);
        return true;
    }
}
