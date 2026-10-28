package com.sism.main.application;

import com.sism.main.interfaces.dto.BusinessImportDtos.ImportType;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「计划内权重合计」参与集合的选择规则（2026-10-28）。
 *
 * <p>运行时分配、下发/异动闭合校验、存量迁移三处必须使用同一集合，
 * 否则会出现「分配完又被校验拦下」。本测试锁定集合选择规则。
 */
@DisplayName("Business Import Weight Scope Tests")
class BusinessImportWeightScopeTest {

    private static Plan plan(Long targetOrgId, Long createdByOrgId, PlanLevel level) {
        Plan plan = Plan.create(4L, targetOrgId, createdByOrgId, level);
        plan.setId(990001L);
        return plan;
    }

    private static Indicator indicator(Long ownerOrgId, Long targetOrgId, boolean parent, boolean deleted) {
        SysOrg owner = SysOrg.create("owner", OrgType.functional);
        owner.setId(ownerOrgId);
        SysOrg target = SysOrg.create("target", OrgType.functional);
        target.setId(targetOrgId);
        Indicator indicator = Indicator.create("测试指标", owner, target, "定量");
        indicator.setId(1L);
        indicator.setWeightPercent(BigDecimal.valueOf(10));
        indicator.setIsDeleted(deleted);
        if (!parent) {
            Indicator parentIndicator = Indicator.create("父指标", owner, owner, "定量");
            parentIndicator.setId(2L);
            indicator.setParent(parentIndicator);
        }
        return indicator;
    }

    @Test
    @DisplayName("Strategic chain counts parent indicators of the plan target org")
    void shouldCountParentIndicatorsForStrategicChain() {
        Plan plan = plan(44L, 35L, PlanLevel.STRAT_TO_FUNC);

        assertTrue(BusinessImportApplicationService.isInWeightScope(
                indicator(35L, 44L, true, false), plan, ImportType.STRATEGIC_TASK));
        // 目标部门不一致 → 不计入
        assertFalse(BusinessImportApplicationService.isInWeightScope(
                indicator(35L, 45L, true, false), plan, ImportType.STRATEGIC_TASK));
        // 软删 → 不计入
        assertFalse(BusinessImportApplicationService.isInWeightScope(
                indicator(35L, 44L, true, true), plan, ImportType.STRATEGIC_TASK));
        // 子指标在战略链不计入
        assertFalse(BusinessImportApplicationService.isInWeightScope(
                indicator(35L, 44L, false, false), plan, ImportType.STRATEGIC_TASK));
    }

    @Test
    @DisplayName("College chain counts child indicators created by the plan owner org")
    void shouldCountOwnChildIndicatorsForCollegeChain() {
        Plan plan = plan(59L, 36L, PlanLevel.FUNC_TO_COLLEGE);

        // 本部门创建的子指标 → 计入
        assertTrue(BusinessImportApplicationService.isInWeightScope(
                indicator(36L, 59L, false, false), plan, ImportType.DISTRIBUTION));
        // 其它部门创建的子指标 → 不计入（与校验侧、迁移侧同口径）
        assertFalse(BusinessImportApplicationService.isInWeightScope(
                indicator(37L, 59L, false, false), plan, ImportType.DISTRIBUTION));
        // 父指标在学院链不计入
        assertFalse(BusinessImportApplicationService.isInWeightScope(
                indicator(36L, 59L, true, false), plan, ImportType.DISTRIBUTION));
        // 目标学院不一致 → 不计入
        assertFalse(BusinessImportApplicationService.isInWeightScope(
                indicator(36L, 61L, false, false), plan, ImportType.DISTRIBUTION));
    }
}
