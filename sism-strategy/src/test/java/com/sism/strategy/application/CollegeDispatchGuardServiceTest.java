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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-05（N11 / PR 审查第六部分最低要求）：{@link CollegeDispatchGuardService} 本体单测。
 *
 * <p>这个守卫的价值全在「不静默失效」：改造前它靠 {@code created_by_org_id} 圈定冲突范围，
 * 语义改成战略部后条件恒不成立 → 不报错、直接放行，比改造前更弱。所以它的三条边界必须钉死：
 * <ol>
 *   <li><b>fail-safe 抛异常</b>：父级缺失且子指标也没有目标组织 → 明确拒绝，不静默放行；</li>
 *   <li><b>父级缺失退回学院目标</b>：父级查不到 → 退回按子指标自身的目标组织（本学院）判定；</li>
 *   <li><b>空计划放行</b>：本计划下没有任何学院子指标 → 无从冲突，放行（不误杀）。</li>
 * </ol>
 */
class CollegeDispatchGuardServiceTest {

    private static final Long PLAN_ID = 7001L;
    private static final Long CYCLE_ID = 2026L;
    private static final Long COLLEGE_ORG_ID = 61L;
    private static final Long FUNCTIONAL_ORG_ID = 44L;

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CollegeDispatchGuardService service = new CollegeDispatchGuardService(jdbcTemplate);

    /** 伪造「本计划下的学院子指标 → 父级核心指标」查询结果。 */
    private void stubCandidates(Long childTargetOrgId, Long parentTargetOrgId) {
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(PLAN_ID)))
                .thenReturn(List.of(new CollegeDispatchGuardService.DispatchSourceCandidate(
                        childTargetOrgId, parentTargetOrgId)));
    }

    private void stubCandidatesEmpty() {
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(PLAN_ID)))
                .thenReturn(List.of());
    }

    private void stubInFlightCount(int count) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(count);
    }

    @Test
    @DisplayName("父级可解析：冲突范围取父级核心指标的目标部门（职能部门），不是子指标自身的学院")
    void shouldDetectInFlightMutationByParentTargetOrg() {
        stubCandidates(COLLEGE_ORG_ID, FUNCTIONAL_ORG_ID);
        stubInFlightCount(1);

        assertTrue(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));

        // 2026-11-05（审查 major #1）：必须锁住「冲突范围用的是父级目标部门」。
        // 之前把 SQL 与参数全部 any() 掉，变异检验发现把实现里的
        // parent.target_org_id 换成 child.target_org_id，9 个用例照样全绿 ——
        // 而这正是本守卫唯一的存在理由（改造后 created_by_org_id 恒不成立 → 静默失效）。
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertTrue(
                java.util.Arrays.asList(args).contains(FUNCTIONAL_ORG_ID),
                "冲突范围应包含父级核心指标的目标部门（职能部门）");
        assertFalse(
                java.util.Arrays.asList(args).contains(COLLEGE_ORG_ID),
                "冲突范围不应包含子指标自身的学院 —— 那是父级缺失时的兜底口径");
    }

    @Test
    @DisplayName("父级缺失：兜底口径改用子指标自身的目标组织（本学院）")
    void shouldFallbackToChildTargetOrgWhenParentMissing() {
        stubCandidates(COLLEGE_ORG_ID, null);
        stubInFlightCount(1);

        assertTrue(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertTrue(
                java.util.Arrays.asList(args).contains(COLLEGE_ORG_ID),
                "父级缺失时应退回按子指标自身的目标组织（本学院）判定");
        assertFalse(
                java.util.Arrays.asList(args).contains(FUNCTIONAL_ORG_ID),
                "父级缺失时不应使用父级目标部门");
    }

    @Test
    @DisplayName("无在审异动：放行")
    void shouldAllowWhenNoMutationInFlight() {
        stubCandidates(COLLEGE_ORG_ID, FUNCTIONAL_ORG_ID);
        stubInFlightCount(0);

        assertFalse(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("父级缺失：退回按子指标自身的目标组织（本学院）判定，不静默放行")
    void shouldFallbackToChildTargetOrgWhenParentMissingKeepsGuardActive() {
        stubCandidates(COLLEGE_ORG_ID, null);
        stubInFlightCount(1);

        assertTrue(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("父级缺失且子指标也没有目标组织：fail-safe 明确抛异常")
    void shouldFailSafeWhenCannotResolveConflictScope() {
        stubCandidates(null, null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID)
        );
        assertTrue(error.getMessage().contains("无法判定本次学院下发所依赖的职能部门"));
    }

    @Test
    @DisplayName("空计划：本计划下没有任何学院子指标 → 无从冲突，放行（不误杀）")
    void shouldAllowEmptyPlan() {
        stubCandidatesEmpty();

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
    @DisplayName("异动侧：存在依赖该职能部门的学院下发审批在途 → 拒绝异动")
    void shouldDetectCollegeDispatchInFlightForOrg() {
        stubInFlightCount(2);

        assertTrue(service.isCollegeDispatchInFlightForOrg(FUNCTIONAL_ORG_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("异动侧：无在途下发审批 → 放行")
    void shouldAllowWhenNoCollegeDispatchInFlight() {
        stubInFlightCount(0);

        assertFalse(service.isCollegeDispatchInFlightForOrg(FUNCTIONAL_ORG_ID, CYCLE_ID));
    }

    @Test
    @DisplayName("计数为 null 时按 0 处理，不抛 NPE")
    void shouldTreatNullCountAsZero() {
        stubCandidates(COLLEGE_ORG_ID, FUNCTIONAL_ORG_ID);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);

        assertFalse(service.isSourceOrgMutationInFlight(PLAN_ID, CYCLE_ID));
    }
}
