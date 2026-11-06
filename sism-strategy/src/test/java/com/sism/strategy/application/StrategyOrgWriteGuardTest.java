package com.sism.strategy.application;

import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05（R6-R9）：指标写入的组织维度授权守卫单测。
 *
 * <p>背景：本次改造只在 create / batch-distribute 两处加了「战略部名下写入须操作者在战略部」
 * 的校验，漏掉了 update / distribute / withdraw 三个入口 —— 独立审查发现职能部门账号可以
 * 改归属、改投、撤回战略部下发的指标，违反需求 3「战略部下发的数据在职能部门只读」。
 *
 * <p>本守卫的判定基准是「指标当前的归属组织」，不是角色码。这样既守住需求 3，
 * 又不会误伤「职能部门维护本部门指标」这一正常业务（回归红线）。
 */
class StrategyOrgWriteGuardTest {

    private static final Long STRATEGY_ORG_ID = 35L;
    private static final Long FUNCTIONAL_ORG_ID = 44L;

    private StrategyOrgWriteGuard guard() {
        return new StrategyOrgWriteGuard(new StrategyOrgProperties());
    }

    private CurrentUser operator(Long orgId, String... roles) {
        List<org.springframework.security.core.authority.SimpleGrantedAuthority> authorities =
                List.of(roles).stream()
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toList();
        return new CurrentUser(1L, "tester", "测试员", "tester@example.com", orgId, authorities);
    }

    @Test
    @DisplayName("R6/R7/R8/R9：战略部下发的指标，职能部门账号不得写入")
    void shouldRejectFunctionalDeptWritingStrategyOwnedIndicator() {
        StrategyOrgWriteGuard guard = guard();
        CurrentUser functionalUser = operator(FUNCTIONAL_ORG_ID, "ROLE_REPORTER");

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> guard.assertOwnerOrgWriteAllowed(functionalUser, STRATEGY_ORG_ID)
        );
        assertTrue(error.getMessage().contains("只有战略发展部成员可以维护战略发展部名下指标"));
    }

    @Test
    @DisplayName("R6/R7/R8/R9：战略部账号可以写入战略部下发的指标")
    void shouldAllowStrategyDeptWritingStrategyOwnedIndicator() {
        StrategyOrgWriteGuard guard = guard();
        CurrentUser strategyUser = operator(STRATEGY_ORG_ID, "ROLE_STRATEGY_DEPT_HEAD");

        assertDoesNotThrow(() -> guard.assertOwnerOrgWriteAllowed(strategyUser, STRATEGY_ORG_ID));
    }

    @Test
    @DisplayName("回归红线：职能部门写入本部门指标不受影响（零变化）")
    void shouldAllowFunctionalDeptWritingOwnIndicator() {
        StrategyOrgWriteGuard guard = guard();
        CurrentUser functionalUser = operator(FUNCTIONAL_ORG_ID, "ROLE_REPORTER");

        assertDoesNotThrow(() -> guard.assertOwnerOrgWriteAllowed(functionalUser, FUNCTIONAL_ORG_ID));
    }

    @Test
    @DisplayName("R6：归属组织取不到时不得当作「已通过」（避免守卫静默失效）")
    void shouldRejectWhenOwnerOrgIsUnknown() {
        StrategyOrgWriteGuard guard = guard();
        CurrentUser functionalUser = operator(FUNCTIONAL_ORG_ID, "ROLE_REPORTER");

        // ownerOrgId 为 null 时守卫不拦截（保持既有行为：不误伤历史数据），
        // 但调用方必须回查指标真实归属组织后再判定，不能拿 null 当判据。
        assertDoesNotThrow(() -> guard.assertOwnerOrgWriteAllowed(functionalUser, null));
    }

    @Test
    @DisplayName("系统管理员可代操作（与导入侧豁免口径一致）")
    void shouldAllowSystemAdminToOperateOnBehalf() {
        StrategyOrgWriteGuard guard = guard();
        CurrentUser systemAdmin = operator(null, "ROLE_SYSTEM_ADMIN");

        assertDoesNotThrow(() -> guard.assertOwnerOrgWriteAllowed(systemAdmin, STRATEGY_ORG_ID));
    }

    @Test
    @DisplayName("战略部 org id 口径唯一：配置缺失时兜底 35")
    void shouldFallbackToDefaultStrategyOrgId() {
        StrategyOrgProperties properties = new StrategyOrgProperties();
        properties.setStrategyOrgId(null);

        StrategyOrgWriteGuard guard = new StrategyOrgWriteGuard(properties);

        assertTrue(guard.isStrategyOrg(STRATEGY_ORG_ID));
        assertThrows(
                SecurityException.class,
                () -> guard.assertOwnerOrgWriteAllowed(
                        operator(FUNCTIONAL_ORG_ID, "ROLE_REPORTER"), STRATEGY_ORG_ID)
        );
    }
}
