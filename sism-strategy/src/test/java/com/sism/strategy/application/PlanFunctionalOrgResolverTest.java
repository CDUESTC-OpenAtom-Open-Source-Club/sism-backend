package com.sism.strategy.application;

import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 2026-11-13 用户拍板（学院上报按归口部门分批提交）：学院计划归口职能部门解析器。
 *
 * 口径：计划下学院子指标 → 父级核心指标.target_org_id 全集，多部门取 MIN；
 * 与迁移 V104 存量回填、导入链、计划提交补写共用本实现，避免口径漂移。
 */
@ExtendWith(MockitoExtension.class)
class PlanFunctionalOrgResolverTest {

    private static final Long PLAN_ID = 705488L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlanRepository planRepository;

    private PlanFunctionalOrgResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PlanFunctionalOrgResolver(jdbcTemplate, planRepository);
    }

    @Test
    @DisplayName("DB 反查：按 plan_id 参数绑定取父级核心指标 target_org_id 全集（去重）")
    void resolveFunctionalOrgIds_shouldBindPlanIdParameter() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(PLAN_ID)))
                .thenReturn(List.of(38L, 37L, 38L));

        Set<Long> orgIds = resolver.resolveFunctionalOrgIds(PLAN_ID);

        assertEquals(Set.of(37L, 38L), orgIds);
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(Long.class), eq(PLAN_ID));
        // 反查口径三要素：父级非空、子指标未删除、父级 target_org 非空（与 V104 回填子查询一致）
        assertTrue(sqlCaptor.getValue().contains("child.parent_indicator_id IS NOT NULL"));
        assertTrue(sqlCaptor.getValue().contains("COALESCE(child.is_deleted, false) = false"));
        assertTrue(sqlCaptor.getValue().contains("parent.target_org_id IS NOT NULL"));
    }

    @Test
    @DisplayName("planId 为空直接返回空集，不打数据库")
    void resolveFunctionalOrgIds_shouldReturnEmptyForNullPlanId() {
        assertTrue(resolver.resolveFunctionalOrgIds(null).isEmpty());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("归口选择：候选非空时直接取 MIN，不触发 DB 反查（导入链批内集合路径）")
    void chooseFunctionalOrgId_shouldPreferCandidatesAndTakeMin() {
        Long chosen = resolver.chooseFunctionalOrgId(PLAN_ID, Set.of(38L, 37L));

        assertEquals(37L, chosen);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("归口选择：候选为空时回退 DB 反查（与 V104 同口径）")
    void chooseFunctionalOrgId_shouldFallBackToDbWhenCandidatesEmpty() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(PLAN_ID)))
                .thenReturn(List.of(38L));

        Long chosen = resolver.chooseFunctionalOrgId(PLAN_ID, Set.of());

        assertEquals(38L, chosen);
    }

    @Test
    @DisplayName("归口选择：全集反查不到 → null（席位回退旧口径）")
    void chooseFunctionalOrgId_shouldReturnNullWhenNothingResolved() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(PLAN_ID)))
                .thenReturn(List.of());

        assertNull(resolver.chooseFunctionalOrgId(PLAN_ID, null));
    }

    @Test
    @DisplayName("提交补写：FUNC_TO_COLLEGE 且归口为空 → 按父级反查落值并保存")
    void backfillFunctionalOrgIfAbsent_shouldFillCollegePlanWithNullOrg() {
        Plan plan = Plan.create(2026L, 36L, 35L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(PLAN_ID);
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(PLAN_ID)))
                .thenReturn(List.of(44L));

        boolean filled = resolver.backfillFunctionalOrgIfAbsent(plan);

        assertTrue(filled);
        assertEquals(44L, plan.getFunctionalOrgId());
        verify(planRepository).save(plan);
    }

    @Test
    @DisplayName("提交补写：已落值 / 非 FUNC_TO_COLLEGE / 无 id → 不动")
    void backfillFunctionalOrgIfAbsent_shouldSkipNonEligiblePlans() {
        Plan alreadySet = Plan.create(2026L, 36L, 35L, PlanLevel.FUNC_TO_COLLEGE);
        alreadySet.setId(1L);
        alreadySet.setFunctionalOrgId(37L);

        Plan strategic = Plan.create(2026L, 35L, 35L, PlanLevel.STRATEGIC);
        strategic.setId(2L);

        Plan noId = Plan.create(2026L, 36L, 35L, PlanLevel.FUNC_TO_COLLEGE);

        assertFalse(resolver.backfillFunctionalOrgIfAbsent(alreadySet));
        assertFalse(resolver.backfillFunctionalOrgIfAbsent(strategic));
        assertFalse(resolver.backfillFunctionalOrgIfAbsent(noId));
        assertFalse(resolver.backfillFunctionalOrgIfAbsent(null));
        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(planRepository);
    }
}
