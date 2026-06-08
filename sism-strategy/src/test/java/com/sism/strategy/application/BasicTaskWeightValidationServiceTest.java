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
        SysOrg ownerOrg = SysOrg.create("职能部门", OrgType.functional);
        ownerOrg.setId(ownerOrgId);
        SysOrg targetOrg = SysOrg.create("学院", OrgType.academic);
        targetOrg.setId(targetOrgId);

        Indicator parent = Indicator.create("父指标", ownerOrg, ownerOrg, "定量");
        parent.setId(9000L + id);

        Indicator indicator = Indicator.create("子指标", ownerOrg, targetOrg, "定量");
        indicator.setId(id);
        indicator.setTaskId(taskId);
        indicator.setParent(parent);
        indicator.setWeightPercent(weight);
        indicator.setIsDeleted(false);
        return indicator;
    }
}
