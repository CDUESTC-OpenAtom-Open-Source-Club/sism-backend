package com.sism.strategy.domain.indicator;

import com.sism.organization.domain.SysOrg;
import com.sism.organization.domain.OrgType;
import com.sism.strategy.domain.indicator.event.IndicatorStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Indicator Aggregate Root Tests")
class IndicatorTest {

    private SysOrg ownerOrg;
    private SysOrg targetOrg;

    @BeforeEach
    void setUp() {
        ownerOrg = SysOrg.create("战略发展部", OrgType.admin);
        targetOrg = SysOrg.create("测试学院", OrgType.academic);
    }

    @Test
    @DisplayName("Should create Indicator with valid parameters")
    void shouldCreateIndicatorWithValidParameters() {
        Indicator indicator = Indicator.create("测试指标", "指标描述", BigDecimal.valueOf(100), ownerOrg, targetOrg, "定性");

        assertNotNull(indicator);
        assertNotNull(indicator.getDescription());
        assertEquals(BigDecimal.valueOf(100), indicator.getWeight());
        assertEquals(ownerOrg, indicator.getOwnerOrg());
        assertEquals(targetOrg, indicator.getTargetOrg());
        assertEquals(IndicatorStatus.DRAFT, indicator.getStatus());
        assertFalse(indicator.getIsDeleted());
        assertNotNull(indicator.getCreatedAt());
        assertNotNull(indicator.getUpdatedAt());
        assertEquals("定性", indicator.getType());
    }

    @Test
    @DisplayName("Should throw exception when creating Indicator with null name")
    void shouldThrowExceptionWhenCreatingIndicatorWithNullName() {
        assertThrows(IllegalArgumentException.class, () ->
            Indicator.create(null, "指标描述", BigDecimal.valueOf(100), ownerOrg, targetOrg, "定量")
        );
    }

    @Test
    @DisplayName("Should throw exception when creating Indicator with negative weight")
    void shouldThrowExceptionWhenCreatingIndicatorWithNegativeWeight() {
        assertThrows(IllegalArgumentException.class, () ->
            Indicator.create("测试指标", "指标描述", BigDecimal.valueOf(-10), ownerOrg, targetOrg, "定量")
        );
    }

    @Test
    @DisplayName("Should throw exception when creating Indicator with weight exceeding 100")
    void shouldThrowExceptionWhenCreatingIndicatorWithWeightExceeding100() {
        assertThrows(IllegalArgumentException.class, () ->
            Indicator.create("测试指标", "指标描述", BigDecimal.valueOf(101), ownerOrg, targetOrg, "定量")
        );
    }

    @Test
    @DisplayName("Should submit indicator for review successfully")
    void shouldSubmitIndicatorForReviewSuccessfully() {
        Indicator indicator = Indicator.create("测试指标", "指标描述", BigDecimal.valueOf(100), ownerOrg, targetOrg, "定量");

        indicator.submitForReview();

        assertEquals(IndicatorStatus.PENDING, indicator.getStatus());
        assertNotNull(indicator.getUpdatedAt());
    }

    @Test
    @DisplayName("Should emit activate event with original status")
    void shouldEmitActivateEventWithOriginalStatus() {
        Indicator indicator = Indicator.create("测试指标", "指标描述", BigDecimal.valueOf(100), ownerOrg, targetOrg, "定量");

        indicator.activate();

        assertEquals(IndicatorStatus.DISTRIBUTED, indicator.getStatus());
        assertEquals(2, indicator.getDomainEvents().size());
        IndicatorStatusChangedEvent event = (IndicatorStatusChangedEvent) indicator.getDomainEvents().get(1);
        assertEquals("DRAFT", event.getOldStatus());
        assertEquals("DISTRIBUTED", event.getNewStatus());
    }

    @Test
    @DisplayName("Should emit terminate event with original status")
    void shouldEmitTerminateEventWithOriginalStatus() {
        Indicator indicator = Indicator.create("测试指标", "指标描述", BigDecimal.valueOf(100), ownerOrg, targetOrg, "定量");

        indicator.terminate("已终止");

        assertEquals(IndicatorStatus.DRAFT, indicator.getStatus());
        IndicatorStatusChangedEvent event = (IndicatorStatusChangedEvent) indicator.getDomainEvents().get(1);
        assertEquals("DRAFT", event.getOldStatus());
        assertEquals("TERMINATED", event.getNewStatus());
    }

    @Test
    @DisplayName("Should validate Indicator with valid parameters")
    void shouldValidateIndicatorWithValidParameters() {
        Indicator indicator = Indicator.create("有效指标", "指标描述", BigDecimal.valueOf(100), ownerOrg, targetOrg, "定量");

        assertDoesNotThrow(indicator::validate);
    }

    @Test
    @DisplayName("Should validate Indicator with invalid parameters")
    void shouldValidateIndicatorWithInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> {
            Indicator.create(null, null, null, null, null, null);
        });
    }

    @Test
    @DisplayName("Should reject missing indicator type instead of defaulting to quantitative")
    void shouldRejectMissingIndicatorType() {
        assertThrows(IllegalArgumentException.class, () ->
            Indicator.create("测试指标", "指标描述", BigDecimal.valueOf(100), ownerOrg, targetOrg, null)
        );
    }

    // ==================== 2026-10-30 回归：层级判定（N-25） ====================

    @Test
    @DisplayName("N-25 回归：职能部门→学院 的子指标应判为 SECOND")
    void shouldCalculateSecondLevelForFunctionalDeptToCollege() {
        // 职能部门 → 二级学院 = 二级指标
        SysOrg funcDept = SysOrg.create("教务处", OrgType.functional);
        SysOrg college = SysOrg.create("工学院", OrgType.academic);

        Indicator indicator = Indicator.create(
                "学院子指标", "子指标描述", BigDecimal.valueOf(100), funcDept, college, "定性");

        assertEquals(IndicatorLevel.SECOND, indicator.calculateLevel(),
                "职能部门→学院的指标必须是 SECOND（修复前恒为 FIRST）");
    }

    @Test
    @DisplayName("N-25 回归：战略发展部→职能部门 的指标应判为 FIRST")
    void shouldCalculateFirstLevelForStrategyDeptToFunctionalDept() {
        SysOrg strategyDept = SysOrg.create("战略发展部", OrgType.admin);
        SysOrg funcDept = SysOrg.create("教务处", OrgType.functional);

        Indicator indicator = Indicator.create(
                "核心指标", "核心指标描述", BigDecimal.valueOf(100), strategyDept, funcDept, "定性");

        assertEquals(IndicatorLevel.FIRST, indicator.calculateLevel());
    }

    @Test
    @DisplayName("N-25 回归：枚举值大小写漂移不会再把层级判错")
    void shouldNotDependOnStringLiteralOrgTypeMatching() {
        // 回归要点：原实现拿 org_type 与 "STRATEGY_DEPT"/"FUNCTIONAL_DEPT" 等字符串字面量比较，
        // 而真实枚举值是小写 admin/functional/academic，比较永不命中 → 全部兜底 FIRST。
        // 现改为直接比较枚举常量，语义只取决于枚举定义本身。
        SysOrg funcDept = SysOrg.create("教务处", OrgType.functional);
        SysOrg college = SysOrg.create("工学院", OrgType.academic);
        Indicator indicator = Indicator.create(
                "学院子指标", "子指标描述", BigDecimal.valueOf(100), funcDept, college, "定性");

        assertEquals("functional", funcDept.getOrgType(),
                "getOrgType() 返回枚举名（小写），不再是 STRATEGY_DEPT 之类的大写字面量");
        assertEquals(IndicatorLevel.SECOND, indicator.calculateLevel());
    }
}
