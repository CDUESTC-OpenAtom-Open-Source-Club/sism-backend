package com.sism.strategy.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CollegeDispatchGuardService} 本体单测。
 *
 * <p>这个守卫的价值全在「不静默失效」：它的锚点一旦恒不成立，就会<b>不报错、直接放行</b>，
 * 比改造前更危险。2026-11-09（单层口径）已经出过一次真实事故：
 * 锚点靠「子指标 → 父级核心指标」推导，而单层后 {@code parent_indicator_id} 恒为 NULL，
 * 原 SQL 的 {@code JOIN indicator parent} 恒不成立 → 异动侧守卫整体静默失效。
 * 因此这里的用例必须钉死三件事：
 * <ol>
 *   <li><b>锚点是「该学院」</b>（不再是父级所属部门），且 SQL 里<b>不得出现 parent JOIN</b>；</li>
 *   <li><b>fail-safe 抛异常</b>：有指标但取不到目标组织 → 明确拒绝，不静默放行；</li>
 *   <li><b>空计划放行</b>：本计划下没有任何指标 → 无从冲突，放行（不误杀）。</li>
 * </ol>
 */
class CollegeDispatchGuardServiceTest {

    private static final Long PLAN_ID = 7001L;
    private static final Long CYCLE_ID = 2026L;
    private static final Long COLLEGE_ORG_ID = 61L;
    private static final Long FUNCTIONAL_ORG_ID = 44L;
    /** 2026-11-17（学院线粒度）：同学院的另一归口部门（B 线）。 */
    private static final Long OTHER_FUNCTIONAL_ORG_ID = 45L;

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CollegeDispatchGuardService service = new CollegeDispatchGuardService(jdbcTemplate);

    /** 伪造「本计划下学院指标 → 目标组织」查询结果。 */
    private void stubTargetOrgIds(Long... targetOrgIds) {
        // 用 doReturn().when() 而不是 when(...).thenReturn(...)：
        // 后者在桩未被消费时会让下一个 when() 抛 UnfinishedStubbingException
        // （有的用例只调异动侧、不查目标组织，query 桩就是未消费的）。
        // 另外 List.of 不允许 null，而 fail-safe 用例恰恰要传 null，
        // 故用 Arrays.asList。
        doReturn(java.util.Arrays.asList(targetOrgIds))
                .when(jdbcTemplate)
                .query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(PLAN_ID));
    }

    private void stubTargetOrgIdsEmpty() {
        doReturn(List.of())
                .when(jdbcTemplate)
                .query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(PLAN_ID));
    }

    /**
     * 2026-11-17（学院线粒度）：伪造「下发计划自身的归口部门」查询结果。
     *
     * <p>SQL 文本与计数查询区分（计数查询里也有 {@code FROM public.plan}），靠
     * {@code SELECT functional_org_id} 这一唯一片段锁定。
     */
    private void stubPlanFunctionalOrgId(Long functionalOrgId) {
        doReturn(functionalOrgId)
                .when(jdbcTemplate)
                .queryForObject(contains("SELECT functional_org_id"), eq(Long.class), eq(PLAN_ID));
    }

    private void stubInFlightCount(int count) {
        doReturn(count)
                .when(jdbcTemplate)
                .queryForObject(anyString(), eq(Integer.class), any(Object[].class));
    }

    @Test
    @DisplayName("锚点取该学院：冲突范围是指标自身的目标组织（学院），并带归口维度")
    void shouldDetectInFlightMutationByCollegeTargetOrg() {
        stubTargetOrgIds(COLLEGE_ORG_ID);
        stubPlanFunctionalOrgId(FUNCTIONAL_ORG_ID);
        stubInFlightCount(1);

        assertTrue(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));

        // 必须锁住「冲突范围用的是指标自身的目标组织」。
        // 之前把 SQL 与参数全部 any() 掉，变异检验发现把实现里的
        // parent.target_org_id 换成 child.target_org_id，用例照样全绿 ——
        // 而这正是本守卫唯一的存在理由。
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertTrue(
                java.util.Arrays.asList(args).contains(COLLEGE_ORG_ID),
                "冲突范围应包含该学院（指标自身的目标组织）");
        // 2026-11-17（学院线粒度）：归口部门现在是「线」的维度，必须参与判定，
        // 否则 A 线的异动在审会把同学院 B 线的下发一并拦死。
        assertTrue(
                java.util.Arrays.asList(args).contains(FUNCTIONAL_ORG_ID),
                "线级互斥必须带上归口维度（否则退化成学院级全锁）");
    }

    /**
     * 2026-11-17（学院线粒度）：下发侧（异动在审 vs 下发）必须按归口收窄到线级。
     *
     * <p>口径：冲突判定 = 同学院 + 同归口。SQL 里必须出现归口维度过滤，
     * 且用 {@code IS NOT DISTINCT FROM}（NULL 与 NULL 同组）而不是 {@code =}
     * （{@code =} 在归口为 NULL 时恒不成立 → 守卫静默失效）。
     */
    @Test
    @DisplayName("2026-11-17：异动侧 SQL 必须按归口收窄到线级（IS NOT DISTINCT FROM）")
    void shouldNarrowMutationSideConflictScopeByFunctionalOrg() {
        stubTargetOrgIds(COLLEGE_ORG_ID);
        stubPlanFunctionalOrgId(FUNCTIONAL_ORG_ID);
        stubInFlightCount(0);

        service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Integer.class), any(Object[].class));
        String sql = sqlCaptor.getValue().toLowerCase();
        assertTrue(sql.contains("functional_org_id is not distinct from"),
                "异动侧冲突范围必须按归口收窄到线级");
        assertFalse(sql.contains("functional_org_id ="),
                "归口为 NULL 时必须与 NULL 同组（IS NOT DISTINCT FROM），用 = 会让守卫静默失效");
    }


    @Test
    @DisplayName("2026-11-09 回归：下发侧 SQL 不得再 JOIN 父级指标（parent 恒为 NULL 会让守卫恒失效）")
    void shouldNotJoinParentIndicatorInSourceQuery() {
        stubTargetOrgIds(COLLEGE_ORG_ID);
        stubInFlightCount(0);

        service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(org.springframework.jdbc.core.RowMapper.class), eq(PLAN_ID));
        String sql = sqlCaptor.getValue().toLowerCase();
        assertFalse(sql.contains("parent_indicator_id"),
                "单层口径下学院指标的 parent_indicator_id 恒为 NULL，JOIN 父级会让冲突范围恒为空 → 守卫静默失效");
        assertFalse(sql.contains("join public.indicator parent"),
                "不得再 JOIN 父级指标表");
    }

    @Test
    @DisplayName("无在审异动：放行")
    void shouldAllowWhenNoMutationInFlight() {
        stubTargetOrgIds(COLLEGE_ORG_ID);
        stubInFlightCount(0);

        assertFalse(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("多个指标指向多个学院：冲突范围含全部目标组织")
    void shouldCollectAllTargetOrgs() {
        stubTargetOrgIds(COLLEGE_ORG_ID, 62L);
        stubInFlightCount(1);

        assertTrue(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), argsCaptor.capture());
        List<Object> args = java.util.Arrays.asList(argsCaptor.getValue());
        assertTrue(args.contains(COLLEGE_ORG_ID), "应包含第一个学院");
        assertTrue(args.contains(62L), "应包含第二个学院");
    }

    @Test
    @DisplayName("有指标但取不到目标组织：fail-safe 明确抛异常，不静默放行")
    void shouldFailSafeWhenCannotResolveConflictScope() {
        stubTargetOrgIds((Long) null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID)
        );
        assertTrue(error.getMessage().contains("无法判定本次学院下发所面向的学院"));
    }

    @Test
    @DisplayName("空计划：本计划下没有任何指标 → 无从冲突，放行（不误杀）")
    void shouldAllowEmptyPlan() {
        stubTargetOrgIdsEmpty();

        assertFalse(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("planId / cycleId 为 null：直接放行（不查库）")
    void shouldAllowWhenArgumentsMissing() {
        assertFalse(service.isSourceOrgMutationInFlight(null, CYCLE_ID));
        assertFalse(service.isSourceOrgMutationInFlight(PLAN_ID, null));
        assertFalse(service.isCollegeDispatchInFlightForOrg(null, CYCLE_ID, FUNCTIONAL_ORG_ID));
        assertFalse(service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, null, FUNCTIONAL_ORG_ID));
    }

    @Test
    @DisplayName("异动侧：存在面向该学院的学院下发审批在途 → 拒绝异动")
    void shouldDetectCollegeDispatchInFlightForOrg() {
        stubInFlightCount(2);

        assertTrue(service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID, FUNCTIONAL_ORG_ID));
    }

    @Test
    @DisplayName("异动侧：无在途下发审批 → 放行")
    void shouldAllowWhenNoCollegeDispatchInFlight() {
        stubInFlightCount(0);

        assertFalse(service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID, FUNCTIONAL_ORG_ID));
    }

    @Test
    @DisplayName("2026-11-09 回归：异动侧 SQL 不得再 JOIN 父级指标（否则恒不成立 → 守卫恒放行）")
    void shouldNotJoinParentIndicatorInMutationSideQuery() {
        stubInFlightCount(0);

        service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID, FUNCTIONAL_ORG_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Integer.class), any(Object[].class));
        String sql = sqlCaptor.getValue().toLowerCase();
        assertFalse(sql.contains("parent_indicator_id"),
                "异动侧若仍靠父级链推导，单层后恒不成立 → 守卫恒放行且不报错");
        assertFalse(sql.contains("join public.indicator parent"),
                "不得再 JOIN 父级指标表");
        // 锚点必须落在指标自身的目标组织上
        assertTrue(sql.contains("c.target_org_id = ?"),
                "异动侧锚点应为指标自身的目标组织");
    }

    /**
     * 2026-11-17（学院线粒度）：下发侧（异动前检查「该学院有没有下发在审」）必须按归口收窄。
     *
     * <p>没有归口维度时，A 线（归口甲）的下发在审会把同学院 B 线（归口乙）的指标异动一并拦死。
     */
    @Test
    @DisplayName("2026-11-17：下发侧 SQL 必须按归口收窄到线级，并把该线归口作为参数传入")
    void shouldNarrowDispatchSideConflictScopeByFunctionalOrg() {
        stubInFlightCount(0);

        service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID, FUNCTIONAL_ORG_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Integer.class), argsCaptor.capture());
        String sql = sqlCaptor.getValue().toLowerCase();
        assertTrue(sql.contains("functional_org_id is not distinct from"),
                "下发侧冲突范围必须按归口收窄到线级");
        assertFalse(sql.contains("functional_org_id ="),
                "归口为 NULL 时必须与 NULL 同组（IS NOT DISTINCT FROM），用 = 会让守卫静默失效");

        List<Object> args = java.util.Arrays.asList(argsCaptor.getValue());
        assertTrue(args.contains(FUNCTIONAL_ORG_ID), "必须把该线归口作为参数传入（否则退化成学院级全锁）");
        assertTrue(args.contains(COLLEGE_ORG_ID), "必须把学院作为参数传入");
        assertTrue(args.contains(CYCLE_ID), "必须把考核周期作为参数传入");
    }

    /**
     * 2026-11-17（学院线粒度）：A 线下发在审时，B 线（同学院、另一归口）的异动不被拦。
     *
     * <p>本用例锁住「归口参数确实进了 SQL」这件事：守卫的判定完全由 SQL 里的
     * {@code p.functional_org_id IS NOT DISTINCT FROM ?} 完成，参数错传就会把
     * B 线也拦死。
     */
    @Test
    @DisplayName("2026-11-17：A 线下发在审不拦 B 线异动（归口不同 → 不算同一条线）")
    void shouldNotBlockOtherFunctionalOrgLineMutationWhenLineDispatchInFlight() {
        stubInFlightCount(0);

        // B 线（归口乙）发起异动 → 守卫按归口查不到在途下发 → 放行
        assertFalse(service.isCollegeDispatchInFlightForOrg(
                COLLEGE_ORG_ID, CYCLE_ID, OTHER_FUNCTIONAL_ORG_ID));

        // 同一条线（归口甲）发起异动 → 同一个在途下发单 → 拦下
        stubInFlightCount(1);
        assertTrue(service.isCollegeDispatchInFlightForOrg(
                COLLEGE_ORG_ID, CYCLE_ID, FUNCTIONAL_ORG_ID));
    }

    /**
     * 2026-11-10（学院下发审批独立流程）：学院下发改走 PLAN_DISPATCH_COLLEGE 后，
     * 在途流程码集合必须包含新码——否则新流程的在途下发拦不住异动（守卫空窗）。
     * 同时保留 STRATEGY（过渡期在途实例）与 FUNCDEPT（历史在途实例）防守卫空窗。
     */
    @Test
    @DisplayName("2026-11-10 回归：异动侧在途流程码必须含新码 PLAN_DISPATCH_COLLEGE 与历史码（防空窗）")
    void shouldMatchAllDispatchFlowCodesIncludingNewCollegeFlow() {
        stubInFlightCount(0);

        service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID, FUNCTIONAL_ORG_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Integer.class), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("PLAN_DISPATCH_COLLEGE"),
                "新流程 PLAN_DISPATCH_COLLEGE 的在途实例必须被守卫覆盖，否则学院下发在途时异动可绕过");
        assertTrue(sql.contains("PLAN_DISPATCH_STRATEGY"),
                "过渡期（2026-11-05~11-09）挂在 flow 1 的在途下发单仍需被守卫覆盖");
        assertTrue(sql.contains("PLAN_DISPATCH_FUNCDEPT"),
                "历史停用流程的在途实例仍需被守卫覆盖（防旧数据守卫空窗）");
    }

    @Test
    @DisplayName("计数为 null 时按 0 处理，不抛 NPE")
    void shouldTreatNullCountAsZero() {
        stubTargetOrgIds(COLLEGE_ORG_ID);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);

        assertFalse(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("守卫两个方向口径对称：同一学院的指标既在异动在审、又有下发在途时互相拦得住")
    void shouldRejectBothDirectionsForSameCollege() {
        // 下发侧：学院指标在异动审批中 → 拒绝下发
        stubTargetOrgIds(COLLEGE_ORG_ID);
        stubPlanFunctionalOrgId(FUNCTIONAL_ORG_ID);
        stubInFlightCount(1);
        assertTrue(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));

        // 异动侧：同一学院同一归口有下发审批在途 → 拒绝异动
        assertTrue(service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID, FUNCTIONAL_ORG_ID));
    }
}
