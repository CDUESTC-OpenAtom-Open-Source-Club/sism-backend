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
     * 归口选择：候选集合为空时回退 DB 反查（与 V104 同口径）；多部门取 id 最小并 warn
     * （一个学院理论上只归口一个职能部门，多部门是口径分裂信号，打 warn 便于运维发现）。
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
