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

    private void stubInFlightCount(int count) {
        doReturn(count)
                .when(jdbcTemplate)
                .queryForObject(anyString(), eq(Integer.class), any(Object[].class));
    }

    @Test
    @DisplayName("锚点取该学院：冲突范围是指标自身的目标组织（学院），不是任何职能部门")
    void shouldDetectInFlightMutationByCollegeTargetOrg() {
        stubTargetOrgIds(COLLEGE_ORG_ID);
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
        assertFalse(
                java.util.Arrays.asList(args).contains(FUNCTIONAL_ORG_ID),
                "冲突范围不应包含职能部门 —— 单层口径下职能部门已不再向学院下发指标");
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
        assertFalse(service.isCollegeDispatchInFlightForOrg(null, CYCLE_ID));
        assertFalse(service.isCollegeDispatchInFlightForOrg(FUNCTIONAL_ORG_ID, null));
    }

    @Test
    @DisplayName("异动侧：存在面向该学院的学院下发审批在途 → 拒绝异动")
    void shouldDetectCollegeDispatchInFlightForOrg() {
        stubInFlightCount(2);

        assertTrue(service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("异动侧：无在途下发审批 → 放行")
    void shouldAllowWhenNoCollegeDispatchInFlight() {
        stubInFlightCount(0);

        assertFalse(service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("2026-11-09 回归：异动侧 SQL 不得再 JOIN 父级指标（否则恒不成立 → 守卫恒放行）")
    void shouldNotJoinParentIndicatorInMutationSideQuery() {
        stubInFlightCount(0);

        service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID);

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
        stubInFlightCount(1);
        assertTrue(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));

        // 异动侧：同一学院有下发审批在途 → 拒绝异动
        assertTrue(service.isCollegeDispatchInFlightForOrg(COLLEGE_ORG_ID, CYCLE_ID));
    }
}
