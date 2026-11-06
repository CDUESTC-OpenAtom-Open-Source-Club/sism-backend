package com.sism.strategy.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 学院指标下发的「互斥守卫」（2026-11-05 战略部直发改造）。
 *
 * <p><b>为什么需要它</b>：改造前，学院下发计划的 {@code created_by_org_id} 是职能部门，
 * 守卫 SQL 靠「下发方 org id = 目标指标的下属部门」来圈定冲突范围。改造后下发方变成战略部
 * （org 35），{@code created_by_org_id} 的语义从「职能部门」变成「战略发展部」，
 * 凡是拿它当筛选条件的守卫都会<b>恒不成立</b>——不报错、不抛异常，直接静默失效。
 * 本类把冲突范围改为<b>显式口径</b>：以「父级核心指标的目标部门（职能部门）」为锚点，
 * 不再依赖 {@code created_by_org_id} 恰好等于某个职能部门。
 *
 * <p><b>两个方向互为镜像</b>：
 * <ul>
 *   <li>{@link #isSourceOrgMutationInFlight}：向学院下发前，检查本次下发所依赖的
 *       职能部门核心指标是否正在异动审批中（在审 → 拒绝下发）；</li>
 *   <li>{@link #isCollegeDispatchInFlightForOrg}：对职能部门指标发起异动前，检查是否
 *       有依赖该部门的学院下发审批在途（在途 → 拒绝异动）。</li>
 * </ul>
 *
 * <p><b>2026-11-05（MA3/MA5）</b>：来源部门靠「子指标 → 父级」实时推导，父级缺失时会推导不出，
 * 必须有兜底锚点 + fail-safe，否则守卫恒放行（比改造前更弱）；同时两个方向的软删口径必须一致，
 * 不能出现「下发侧已放行、异动侧仍拦」的自相矛盾。
 */
@Service
@RequiredArgsConstructor
public class CollegeDispatchGuardService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 本下发计划所依赖的职能部门（= 学院子指标的父级核心指标的目标部门）是否正在异动审批中。
     *
     * <p>显式口径：本计划（{@code plan_id}）下的学院子指标 → 其父级核心指标 → 父级的目标部门。
     * 父级核心指标是「战略部下发给职能部门的那批核心指标」，它的目标部门就是本次下发的数据来源部门。
     * 该部门的指标正在异动（PLAN_MUTATION_STRATEGY / IN_REVIEW）时，向学院下发会与在审口径冲突。
     *
     * <p><b>2026-11-05（MA3）兜底锚点</b>：来源部门是「子指标 → 父级」实时推导出来的，
     * 父级缺失（数据异常 / 存量计划）时子查询为空 → count = 0 → 守卫恒放行，
     * 比改造前（只看 plan.created_by_org_id，不依赖子指标存在）更弱。因此：
     * <ol>
     *   <li>父级可解析 → 用父级的目标部门（职能部门）作为冲突范围（主口径，不变）；</li>
     *   <li>父级缺失但子指标有目标组织 → 退回按「本计划下目标为本学院的子指标」判定
     *       （等价于 2026-10-31 定案的学院级联全锁），至少不会静默放行；</li>
     *   <li>两者都取不到 → <b>fail-safe 明确拒绝</b>，不静默放行；
     *       但「本计划下没有任何学院子指标」的空计划不误杀（无从冲突，直接放行）。</li>
     * </ol>
     *
     * @param planId  学院下发计划 ID
     * @param cycleId 考核周期 ID
     * @return true=存在在审异动（应拒绝下发）
     */
    public boolean isSourceOrgMutationInFlight(Long planId, Long cycleId) {
        if (planId == null || cycleId == null) {
            return false;
        }
        List<DispatchSourceCandidate> candidates = loadDispatchSourceCandidates(planId);
        if (candidates.isEmpty()) {
            // 本计划下没有学院子指标 → 无从冲突，放行（空计划不是异常，不误杀）
            return false;
        }
        List<Long> conflictOrgIds = candidates.stream()
                .map(DispatchSourceCandidate::parentTargetOrgId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (conflictOrgIds.isEmpty()) {
            // 兜底锚点：父级缺失 → 退回按子指标自身的目标组织（本学院）判定
            conflictOrgIds = candidates.stream()
                    .map(DispatchSourceCandidate::childTargetOrgId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (conflictOrgIds.isEmpty()) {
                throw new IllegalStateException(
                        "无法判定本次学院下发所依赖的职能部门（学院子指标缺少父级核心指标且缺少目标组织），"
                                + "已阻止下发；请先修正指标数据后再下发");
            }
        }
        return countInFlightMutationTargets(cycleId, conflictOrgIds) > 0;
    }

    /**
     * 本计划下的学院子指标 → 父级核心指标 → 父级目标部门 / 子指标目标组织。
     * 软删的子指标、软删的父级、软删的任务一律不算（口径与 isCollegeDispatchInFlightForOrg 保持一致）。
     */
    private List<DispatchSourceCandidate> loadDispatchSourceCandidates(Long planId) {
        return jdbcTemplate.query(
                """
                SELECT child.target_org_id AS child_target_org_id,
                       parent.target_org_id AS parent_target_org_id
                FROM public.indicator child
                JOIN public.sys_task ct ON ct.task_id = child.task_id
                LEFT JOIN public.indicator parent
                       ON parent.id = child.parent_indicator_id
                      AND COALESCE(parent.is_deleted, false) = false
                WHERE ct.plan_id = ?
                  AND COALESCE(ct.is_deleted, false) = false
                  AND COALESCE(child.is_deleted, false) = false
                """,
                (rs, rowNum) -> new DispatchSourceCandidate(
                        nullableLong(rs, "child_target_org_id"),
                        nullableLong(rs, "parent_target_org_id")),
                planId
        );
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** 指定目标组织集合中，存在本周期在审异动（PLAN_MUTATION_STRATEGY）的指标数。 */
    private int countInFlightMutationTargets(Long cycleId, List<Long> targetOrgIds) {
        if (targetOrgIds == null || targetOrgIds.isEmpty()) {
            return 0;
        }
        String placeholders = targetOrgIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[targetOrgIds.size() + 1];
        args[0] = cycleId;
        for (int index = 0; index < targetOrgIds.size(); index++) {
            args[index + 1] = targetOrgIds.get(index);
        }
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                JOIN public.indicator i ON ai.entity_type = 'INDICATOR' AND i.id = ai.entity_id
                JOIN public.sys_task t ON t.task_id = i.task_id
                JOIN public.plan p ON p.id = t.plan_id
                WHERE ai.status = 'IN_REVIEW'
                  AND p.cycle_id = ?
                  AND COALESCE(i.is_deleted, false) = false
                  AND i.target_org_id IN (%s)
                """.formatted(placeholders),
                Integer.class,
                args
        );
        return count == null ? 0 : count;
    }

    /** 学院子指标的「自身目标组织」与「父级核心指标的目标部门」。 */
    private record DispatchSourceCandidate(Long childTargetOrgId, Long parentTargetOrgId) {
    }

    /**
     * 是否存在「依赖该职能部门」的学院下发审批在途。
     *
     * <p>显式口径：FUNC_TO_COLLEGE 级别的下发计划（学院目标），其学院子指标的父级核心指标
     * 的目标部门 == 待异动指标的目标部门时，说明该职能部门正在被拆解到学院，
     * 此时发起异动会让在审的下发快照失效。
     *
     * <p>flow_code 同时保留 {@code PLAN_DISPATCH_FUNCDEPT}：该流程虽已停用（B9），
     * 但历史库中可能仍有在途实例，保留可避免旧数据出现守卫空窗。
     *
     * @param targetOrgId 待异动指标的目标部门（职能部门）
     * @param cycleId     考核周期 ID
     * @return true=存在在途下发审批（应拒绝异动）
     */
    public boolean isCollegeDispatchInFlightForOrg(Long targetOrgId, Long cycleId) {
        if (targetOrgId == null || cycleId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code IN ('PLAN_DISPATCH_STRATEGY', 'PLAN_DISPATCH_FUNCDEPT')
                JOIN public.plan p ON p.id = ai.entity_id
                WHERE ai.entity_type = 'PLAN'
                  AND ai.status = 'IN_REVIEW'
                  AND p.plan_level = 'FUNC_TO_COLLEGE'
                  AND p.cycle_id = ?
                  AND COALESCE(p.is_deleted, false) = false
                  AND EXISTS (
                      SELECT 1
                      FROM public.sys_task t
                      JOIN public.indicator c ON c.task_id = t.task_id
                      JOIN public.indicator parent ON parent.id = c.parent_indicator_id
                      WHERE t.plan_id = p.id
                        -- 2026-11-05（MA5）：软删口径与 isSourceOrgMutationInFlight 统一——
                        -- 子指标或父级被软删后，本次下发已不再依赖它，不该再据此拦住异动；
                        -- 原口径不过滤 is_deleted，会出现「一边放行、一边仍拦」的自相矛盾。
                        AND COALESCE(c.is_deleted, false) = false
                        AND COALESCE(parent.is_deleted, false) = false
                        AND parent.target_org_id = ?
                  )
                """,
                Integer.class,
                cycleId,
                targetOrgId);
        return count != null && count > 0;
    }
}
