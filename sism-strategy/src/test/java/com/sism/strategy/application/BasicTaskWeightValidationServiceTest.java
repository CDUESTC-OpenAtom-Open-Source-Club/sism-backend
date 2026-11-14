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

    // ============ 2026-10-31 用户拍板（2026-11-08 单层口径修订）：被拦时点名「无法计入基础性权重合计」的指标 ============

    @Test
    @DisplayName("2026-10-31 用户拍板（单层口径修订）：学院计划无基础性任务被拦时，点名落在发展性任务上的学院指标")
    void shouldNameNonBasicIndicatorsWhenFuncToCollegePlanHasNoBasicTask() {
        Plan plan = Plan.create(4L, 55L, 36L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403655L);

        // 2026-11-08：学院指标没有父级，点名对象改为指标自身
        Indicator developmentChild = buildCollegeIndicator(
                2041L, 41023L, 36L, 55L, "发展性指标甲", BigDecimal.valueOf(100));

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

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.validatePlanBasicWeight(403655L, 55L)
        );

        String message = error.getMessage();
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("学院指标『发展性指标甲』所属任务为“发展性”"),
                "应点名校验指标及其任务类型: " + message);
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("无法计入基础性权重合计"),
                "应说明无法计入基础性权重合计: " + message);
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("按学院整体合计须为 100"),
                "应给出学院整体合计要求: " + message);
    }

    @Test
    @DisplayName("2026-10-31 用户拍板：超过 3 个时列前 3 个并以「等 N 项」收尾（GateMessages 风格）")
    void shouldLimitNamedIndicatorsToThreeWithCount() {
        Plan plan = Plan.create(4L, 55L, 36L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403655L);

        // 4 条指标分别落在 4 个发展性任务上（新口径：没有父级，点名指标自身）
        List<Indicator> children = List.of(
                buildCollegeIndicator(2041L, 41021L, 36L, 55L, "指标甲", BigDecimal.valueOf(10)),
                buildCollegeIndicator(2042L, 41022L, 36L, 55L, "指标乙", BigDecimal.valueOf(20)),
                buildCollegeIndicator(2043L, 41023L, 36L, 55L, "指标丙", BigDecimal.valueOf(30)),
                buildCollegeIndicator(2044L, 41024L, 36L, 55L, "指标丁", BigDecimal.valueOf(40)));
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

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.validatePlanBasicWeight(403655L, 55L)
        );

        String message = error.getMessage();
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("『指标甲』『指标乙』『指标丙』 等 4 项"),
                "应列前 3 个并以「等 N 项」收尾: " + message);
        org.junit.jupiter.api.Assertions.assertFalse(
                message.contains("指标丁"),
                "第 4 个不应被点名: " + message);
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

    // ============ 2026-11-05（学院指标直发改造）：合计范围不再依赖「归属组织 == 计划创建组织」 ============
    // 改造后 FUNC_TO_COLLEGE 计划的创建组织恒为战略部（35），子指标的归属组织也是战略部，
    // 旧口径「owner == created_by」的筛选条件语义漂移后要么恒成立、要么恒不成立，
    // 锁不住新语义——这里显式覆盖「owner = created_by」与「owner ≠ created_by」两种情形。

    @Test
    @DisplayName("2026-11-05：计划创建组织=35、子指标归属组织=35、合计 100 → 通过")
    void shouldPassWhenStrategyOwnedCollegeChildrenSumToHundred() {
        Plan plan = Plan.create(4L, 55L, 35L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403700L);

        Indicator childA = buildChildIndicator(2101L, 41101L, 35L, 55L, BigDecimal.valueOf(60));
        Indicator childB = buildChildIndicator(2102L, 41101L, 35L, 55L, BigDecimal.valueOf(40));

        when(planRepository.findById(403700L)).thenReturn(java.util.Optional.of(plan));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("t.task_type = 'BASIC'"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(403700L)))
                .thenReturn(List.of(41101L));
        when(indicatorRepository.findByTaskIds(List.of(41101L))).thenReturn(List.of(childA, childB));

        assertDoesNotThrow(() -> service.validatePlanBasicWeight(403700L, 55L));
        verify(indicatorRepository).findByTaskIds(List.of(41101L));
    }

    @Test
    @DisplayName("2026-11-05：子指标归属组织 ≠ 计划创建组织时合计口径不变（owner 不再是筛选条件）")
    void shouldNotFilterByOwnerOrgWhenOwnerDiffersFromPlanCreator() {
        Plan plan = Plan.create(4L, 55L, 35L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403701L);

        // 归属组织 = 36（≠ 计划创建组织 35），目标仍是学院 55，合计 100
        Indicator childA = buildChildIndicator(2103L, 41102L, 36L, 55L, BigDecimal.valueOf(70));
        Indicator childB = buildChildIndicator(2104L, 41102L, 36L, 55L, BigDecimal.valueOf(30));

        when(planRepository.findById(403701L)).thenReturn(java.util.Optional.of(plan));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("t.task_type = 'BASIC'"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(403701L)))
                .thenReturn(List.of(41102L));
        when(indicatorRepository.findByTaskIds(List.of(41102L))).thenReturn(List.of(childA, childB));

        // 新口径：合计范围 = 本计划 BASIC 任务 + 子指标 + 目标为该学院，与归属组织无关 → 仍然通过
        assertDoesNotThrow(() -> service.validatePlanBasicWeight(403701L, 55L));
    }

    @Test
    @DisplayName("2026-11-05：子指标归属组织 ≠ 计划创建组织时，合计不足 100 仍要被拦（合计校验不因 owner 放宽）")
    void shouldStillRejectWhenOwnerDiffersFromPlanCreatorAndSumIsNotHundred() {
        Plan plan = Plan.create(4L, 55L, 35L, PlanLevel.FUNC_TO_COLLEGE);
        plan.setId(403702L);

        Indicator childA = buildChildIndicator(2105L, 41103L, 36L, 55L, BigDecimal.valueOf(40));
        Indicator childB = buildChildIndicator(2106L, 41103L, 36L, 55L, BigDecimal.valueOf(30));

        when(planRepository.findById(403702L)).thenReturn(java.util.Optional.of(plan));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("t.task_type = 'BASIC'"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(403702L)))
                .thenReturn(List.of(41103L));
        when(indicatorRepository.findByTaskIds(List.of(41103L))).thenReturn(List.of(childA, childB));
        // 点名父级的补充查询（找不到可点名父级时回退原有文案）
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("SELECT t.task_id, t.task_type"),
                org.mockito.ArgumentMatchers.eq(403702L)))
                .thenReturn(List.of(java.util.Map.of("task_id", 41103L, "task_type", "BASIC")));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.validatePlanBasicWeight(403702L, 55L)
        );

        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().contains("学院指标权重合计必须为100"),
                "合计不足 100 时应被拦: " + error.getMessage());
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

    /**
     * 2026-11-08（学院指标单层口径）：学院指标没有父级，这是新口径下的标准形态——
     * 用于验证「点名被拦指标」的文案（旧口径点名的是父级核心指标）。
     */
    private Indicator buildCollegeIndicator(Long id,
                                            Long taskId,
                                            Long ownerOrgId,
                                            Long targetOrgId,
                                            String indicatorName,
                                            BigDecimal weight) {
        SysOrg ownerOrg = SysOrg.create("战略发展部", OrgType.admin);
        ownerOrg.setId(ownerOrgId);
        SysOrg targetOrg = SysOrg.create("学院", OrgType.academic);
        targetOrg.setId(targetOrgId);

        Indicator indicator = Indicator.create(indicatorName, ownerOrg, targetOrg, "定量");
        indicator.setId(id);
        indicator.setTaskId(taskId);
        indicator.setWeightPercent(weight);
        indicator.setIsDeleted(false);
        return indicator;
    }
}
