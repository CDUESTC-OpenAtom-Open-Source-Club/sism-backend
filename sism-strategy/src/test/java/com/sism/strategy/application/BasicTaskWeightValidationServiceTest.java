package com.sism.strategy.application;

import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.repository.IndicatorRepository;
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

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Basic Task Weight Validation Service Tests")
class BasicTaskWeightValidationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private IndicatorRepository indicatorRepository;

    @Mock
    private PlanRepository planRepository;

    private BasicTaskWeightValidationService service;

    @BeforeEach
    void setUp() {
        service = new BasicTaskWeightValidationService(jdbcTemplate, indicatorRepository, planRepository);
    }

    @Test
    @DisplayName("Should pass when basic root indicator weights sum to 100")
    void shouldPassWhenBasicWeightEquals100() {
        Indicator indicatorA = buildIndicator(2001L, 1001L, 49L, BigDecimal.valueOf(40));
        Indicator indicatorB = buildIndicator(2002L, 1001L, 49L, BigDecimal.valueOf(60));

        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(List.of(1001L));
        when(indicatorRepository.findByTaskIds(List.of(1001L))).thenReturn(List.of(indicatorA, indicatorB));

        assertDoesNotThrow(() -> service.validatePlanBasicWeight(7L, 49L));
        verify(indicatorRepository).findByTaskIds(List.of(1001L));
        verify(indicatorRepository, never()).findAll();
    }

    @Test
    @DisplayName("Should fail when basic root indicator weights do not sum to 100")
    void shouldFailWhenBasicWeightNotEquals100() {
        Indicator indicatorA = buildIndicator(2001L, 1001L, 49L, BigDecimal.valueOf(25));
        Indicator indicatorB = buildIndicator(2002L, 1001L, 49L, BigDecimal.valueOf(25));

        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(List.of(1001L));
        when(indicatorRepository.findByTaskIds(List.of(1001L))).thenReturn(List.of(indicatorA, indicatorB));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.validatePlanBasicWeight(7L, 49L)
        );

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("基础性任务指标权重合计必须为100"));
        verify(indicatorRepository).findByTaskIds(List.of(1001L));
        verify(indicatorRepository, never()).findAll();
    }

    @Test
    @DisplayName("Should ignore development child indicators when validating func-to-college plan")
    void shouldIgnoreDevelopmentChildIndicatorsWhenValidatingFuncToCollegePlan() {
        Plan plan = Plan.create(4L, 55L, 36L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403655L);

        Indicator basicChildA = buildChildIndicator(2042L, 41001L, 36L, 55L, BigDecimal.valueOf(50));
        Indicator basicChildB = buildChildIndicator(2043L, 41001L, 36L, 55L, BigDecimal.valueOf(50));
        Indicator developmentChild = buildChildIndicator(2041L, 41023L, 36L, 55L, BigDecimal.valueOf(40));

        when(planRepository.findById(403655L)).thenReturn(java.util.Optional.of(plan));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("t.plan_id = ?"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(403655L)))
                .thenReturn(List.of(41001L));
        when(indicatorRepository.findByTaskIds(List.of(41001L)))
                .thenReturn(List.of(basicChildA, basicChildB));

        assertDoesNotThrow(() -> service.validatePlanBasicWeight(403655L, 55L));
        verify(indicatorRepository).findByTaskIds(List.of(41001L));
    }

    // ============ 2026-10-31 用户拍板：学院计划被拦时点名「无法计入基础性权重合计」的父级 ============

    @Test
    @DisplayName("2026-10-31 用户拍板：学院计划无基础性任务被拦时，点名所属任务为发展性的父级核心指标")
    void shouldNameNonBasicParentsWhenFuncToCollegePlanHasNoBasicTask() {
        Plan plan = Plan.create(4L, 55L, 36L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403655L);

        // 学院子指标全部落在发展性任务上（父级 = 职能部门核心指标「父指标」）
        Indicator developmentChild = buildChildIndicator(2041L, 41023L, 36L, 55L, BigDecimal.valueOf(100));

        when(planRepository.findById(403655L)).thenReturn(java.util.Optional.of(plan));
        // 基础性任务查询返回空 → 「不存在基础性任务」分支
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("t.task_type = 'BASIC'"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(403655L)))
                .thenReturn(List.of());
        // 全量任务查询（含任务类型）：只有发展性任务
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("SELECT t.task_id, t.task_type"),
                org.mockito.ArgumentMatchers.eq(403655L)))
                .thenReturn(List.of(java.util.Map.of("task_id", 41023L, "task_type", "DEVELOPMENT")));
        when(indicatorRepository.findByTaskIds(List.of(41023L))).thenReturn(List.of(developmentChild));
        when(indicatorRepository.findByIds(List.of(11041L))).thenReturn(List.of(developmentChild.getParent()));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.validatePlanBasicWeight(403655L, 55L)
        );

        String message = error.getMessage();
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("父级核心指标『父指标』所属任务为“发展性”"),
                "应点名父级核心指标及其任务类型: " + message);
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("无法计入基础性权重合计"),
                "应说明无法计入基础性权重合计: " + message);
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("基础性指标权重全计划合计须为 100"),
                "应给出计划级合计要求: " + message);
    }

    @Test
    @DisplayName("2026-10-31 用户拍板：父级超过 3 个时列前 3 个并以「等 N 项」收尾（GateMessages 风格）")
    void shouldLimitNamedParentsToThreeWithCount() {
        Plan plan = Plan.create(4L, 55L, 36L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403655L);

        // 4 个发展性子指标分别挂在 4 个不同父级上（父级 ID = 9000 + 子指标 ID）
        List<Indicator> children = List.of(
                buildNamedChildIndicator(2041L, 41021L, 36L, 55L, "父指标甲"),
                buildNamedChildIndicator(2042L, 41022L, 36L, 55L, "父指标乙"),
                buildNamedChildIndicator(2043L, 41023L, 36L, 55L, "父指标丙"),
                buildNamedChildIndicator(2044L, 41024L, 36L, 55L, "父指标丁"));
        when(planRepository.findById(403655L)).thenReturn(java.util.Optional.of(plan));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("t.task_type = 'BASIC'"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(403655L)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("SELECT t.task_id, t.task_type"),
                org.mockito.ArgumentMatchers.eq(403655L)))
                .thenReturn(List.of(
                        java.util.Map.of("task_id", 41021L, "task_type", "DEVELOPMENT"),
                        java.util.Map.of("task_id", 41022L, "task_type", "DEVELOPMENT"),
                        java.util.Map.of("task_id", 41023L, "task_type", "DEVELOPMENT"),
                        java.util.Map.of("task_id", 41024L, "task_type", "DEVELOPMENT")));
        when(indicatorRepository.findByTaskIds(List.of(41021L, 41022L, 41023L, 41024L))).thenReturn(children);
        when(indicatorRepository.findByIds(List.of(11041L, 11042L, 11043L, 11044L))).thenReturn(
                List.of(children.get(0).getParent(),
                        children.get(1).getParent(),
                        children.get(2).getParent(),
                        children.get(3).getParent()));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.validatePlanBasicWeight(403655L, 55L)
        );

        String message = error.getMessage();
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("『父指标甲』『父指标乙』『父指标丙』 等 4 项"),
                "应列前 3 个并以「等 N 项」收尾: " + message);
        org.junit.jupiter.api.Assertions.assertFalse(
                message.contains("父指标丁"),
                "第 4 个父级不应被点名: " + message);
    }

    @Test
    @DisplayName("2026-10-31 用户拍板：找不到可点名的父级时回退原有文案（不误报）")
    void shouldFallBackToLegacyMessageWhenNoNonBasicParentsFound() {
        Plan plan = Plan.create(4L, 55L, 36L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403655L);

        when(planRepository.findById(403655L)).thenReturn(java.util.Optional.of(plan));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("t.task_type = 'BASIC'"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(403655L)))
                .thenReturn(List.of());
        // 计划下没有任何任务（更无子指标）→ 无可点名父级 → 回退原文案
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("SELECT t.task_id, t.task_type"),
                org.mockito.ArgumentMatchers.eq(403655L)))
                .thenReturn(List.of());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.validatePlanBasicWeight(403655L, 55L)
        );

        org.junit.jupiter.api.Assertions.assertEquals("当前计划不存在基础性任务，不能下发", error.getMessage());
    }

    private Indicator buildIndicator(Long id, Long taskId, Long targetOrgId, BigDecimal weight) {
        SysOrg ownerOrg = SysOrg.create("战略发展部", OrgType.admin);
        SysOrg targetOrg = SysOrg.create("实验室建设管理处", OrgType.admin);
        targetOrg.setId(targetOrgId);

        Indicator indicator = Indicator.create("测试指标", ownerOrg, targetOrg, "定量");
        indicator.setId(id);
        indicator.setTaskId(taskId);
        indicator.setParent(null);
        indicator.setWeightPercent(weight);
        indicator.setIsDeleted(false);
        return indicator;
    }

    private Indicator buildChildIndicator(Long id,
                                          Long taskId,
                                          Long ownerOrgId,
                                          Long targetOrgId,
                                          BigDecimal weight) {
        Indicator indicator = buildNamedChildIndicator(id, taskId, ownerOrgId, targetOrgId, "父指标");
        indicator.setWeightPercent(weight);
        return indicator;
    }

    /** 父级指标名可指定：父级 ID = 9000 + 子指标 ID（点名文案测试用，父级名即被点名内容） */
    private Indicator buildNamedChildIndicator(Long id,
                                               Long taskId,
                                               Long ownerOrgId,
                                               Long targetOrgId,
                                               String parentName) {
        SysOrg ownerOrg = SysOrg.create("职能部门", OrgType.functional);
        ownerOrg.setId(ownerOrgId);
        SysOrg targetOrg = SysOrg.create("学院", OrgType.academic);
        targetOrg.setId(targetOrgId);

        Indicator parent = Indicator.create(parentName, ownerOrg, ownerOrg, "定量");
        parent.setId(9000L + id);

        Indicator indicator = Indicator.create("子指标", ownerOrg, targetOrg, "定量");
        indicator.setId(id);
        indicator.setTaskId(taskId);
        indicator.setParent(parent);
        indicator.setIsDeleted(false);
        return indicator;
    }
}
