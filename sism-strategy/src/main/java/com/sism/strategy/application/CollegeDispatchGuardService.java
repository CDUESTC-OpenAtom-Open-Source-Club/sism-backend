package com.sism.strategy.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 学院指标下发的「互斥守卫」（2026-11-05 战略部直发改造；2026-11-09 单层口径再调整）。
 *
 * <p><b>为什么需要它</b>：同一个学院的同一批指标，可能同时处在「异动审批中」和「下发审批中」。
 * 两个审批各自基于提交时的快照推进，若都放行，最终落库的数据与两份快照都对不上
 * （下发快照里的权重 / 名称被异动改掉，或反之），且事后无法追溯是哪一份生效。
 * 所以两个方向必须互斥。
 *
 * <p><b>2026-11-05（第一版锚点）</b>：靠「子指标 → 父级核心指标 → 父级目标部门（职能部门）」推导冲突范围。
 * <b>2026-11-09（单层口径）锚点改为「该学院」</b>，原因有三：
 * <ol>
 *   <li><b>原锚点恒失效</b>：学院指标改为「战略部 → 学院」单层直发后 {@code parent_indicator_id}
 *       恒为 NULL，原 SQL 里的 {@code JOIN indicator parent ON parent.id = c.parent_indicator_id}
 *       恒不成立 → 异动侧守卫恒放行，且不报错（静默失效，比改造前更弱）。</li>
 *   <li><b>原依赖关系已消失</b>：改造前学院指标是从职能部门核心指标拆出来的，所以「部门指标在异动」
 *       会让下发的数据失效；单层后职能部门<b>不再</b>向学院下发指标，两边没有这层依赖，
 *       继续按部门锚定会误拦（例如教务处自己的指标在异动，却挡住给学院下发）。</li>
 *   <li><b>还成立的关系只有一条</b>：同一批学院指标本身。
 *       因此锚点统一改为「计划/指标的目标组织 = 该学院」，两个方向对称。</li>
 * </ol>
 *
 * <p><b>两个方向互为镜像</b>：
 * <ul>
 *   <li>{@link #isSourceOrgMutationInFlight}：向学院下发前，检查该学院的指标是否正在异动审批中
 *       （在审 → 拒绝下发）；</li>
 *   <li>{@link #isCollegeDispatchInFlightForOrg}：对某组织的指标发起异动前，检查该组织是否
 *       正有学院下发审批在途（在途 → 拒绝异动）。
 *       对职能部门指标发起异动时自然放行（职能部门已不是任何学院指标的目标组织）。</li>
 * </ul>
 *
 * <p><b>fail-safe 原则</b>：判不出冲突范围时<b>明确拒绝</b>，绝不静默放行
 * （比改造前更弱是这类守卫最危险的失效方式）。
 */
@Service
@RequiredArgsConstructor
public class CollegeDispatchGuardService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 本下发计划所面向的学院，其指标是否正在异动审批中。
     *
     * <p>显式口径（2026-11-09 单层口径）：本计划（{@code plan_id}）下学院指标的目标组织 = 该学院。
     * 该学院的指标正在异动（PLAN_MUTATION_STRATEGY / IN_REVIEW）时，向它下发会与在审口径冲突。
     *
     * <p><b>fail-safe</b>：
     * <ol>
     *   <li>本计划下没有任何指标 → 无从冲突，放行（空计划不是异常，不误杀）；</li>
     *   <li>有指标但取不到目标组织 → <b>明确拒绝</b>（抛 IllegalStateException），不静默放行。</li>
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
        List<Long> targetOrgIds = loadDispatchTargetOrgIds(planId);
        if (targetOrgIds.isEmpty()) {
            // 本计划下没有指标 → 无从冲突，放行（空计划不是异常，不误杀）
            return false;
        }
        if (targetOrgIds.stream().anyMatch(Objects::isNull)) {
            // fail-safe：判不出冲突范围就明确拒绝，绝不静默放行
            throw new IllegalStateException(
                    "无法判定本次学院下发所面向的学院（计划下存在缺少目标组织的指标），"
                            + "已阻止下发；请先修正指标数据后再下发");
        }
        return countInFlightMutationTargets(cycleId, targetOrgIds) > 0;
    }

    /**
     * 本计划下的学院指标 → 其目标组织（即该学院）。
     *
     * <p>2026-11-09（单层口径）：不再 JOIN 父级指标。
     * 单层后 {@code parent_indicator_id} 恒为 NULL，原 JOIN 恒不成立 → 异动侧守卫恒放行（静默失效）。
     * 软删的指标、软删的任务一律不算（与异动侧口径保持一致，避免「一边放行、一边仍拦」）。
     */
    private List<Long> loadDispatchTargetOrgIds(Long planId) {
        return jdbcTemplate.query(
                """
                SELECT c.target_org_id AS target_org_id
                FROM public.indicator c
                JOIN public.sys_task ct ON ct.task_id = c.task_id
                WHERE ct.plan_id = ?
                  AND COALESCE(ct.is_deleted, false) = false
                  AND COALESCE(c.is_deleted, false) = false
                """,
                (rs, rowNum) -> nullableLong(rs, "target_org_id"),
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

    /**
     * 是否存在「面向该组织」的学院下发审批在途。
     *
     * <p>显式口径（2026-11-09 单层口径）：FUNC_TO_COLLEGE 级别的下发计划（学院目标），
     * 其下学院指标的目标组织 == 待异动指标的目标组织时，说明该组织的指标正被下发审批锁住，
     * 此时发起异动会让在审的下发快照失效。
     *
     * <p>对<b>职能部门</b>指标发起异动时自然放行：职能部门已不再向学院下发指标，
     * 不是任何学院指标的目标组织（原口径靠父级链推导，单层后该链不存在）。
     *
     * <p>flow_code 同时保留 {@code PLAN_DISPATCH_FUNCDEPT}：该流程虽已停用（V101），
     * 但历史库中可能仍有在途实例，保留可避免旧数据出现守卫空窗。
     *
     * @param targetOrgId 待异动指标的目标组织（学院；职能部门自然不命中）
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
                      WHERE t.plan_id = p.id
                        -- 2026-11-05（MA5）：软删口径与 isSourceOrgMutationInFlight 统一——
                        -- 指标被软删后，本次下发已不再依赖它，不该再据此拦住异动；
                        -- 原口径不过滤 is_deleted，会出现「一边放行、一边仍拦」的自相矛盾。
                        AND COALESCE(c.is_deleted, false) = false
                        -- 2026-11-09（单层口径）：锚点由「父级核心指标的目标部门」改为「指标自身的目标组织」，
                        -- 并要求与计划的目标学院一致 —— 原 JOIN parent 在 parent 恒为 NULL 时恒不成立，
                        -- 会让这个守卫整体静默失效（不报错、直接放行）。
                        AND c.target_org_id = ?
                        AND c.target_org_id = p.target_org_id
                  )
                """,
                Integer.class,
                cycleId,
                targetOrgId);
        return count != null && count > 0;
    }
}
