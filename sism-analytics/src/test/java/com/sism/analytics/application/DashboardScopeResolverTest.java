package com.sism.analytics.application;

import com.sism.analytics.domain.repository.DashboardScopeQueryRepository;
import com.sism.shared.application.dto.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DashboardScopeResolver 单测（2026-11-27 看板角色分流）。
 */
class DashboardScopeResolverTest {

    private final DashboardScopeQueryRepository queryRepository = mock(DashboardScopeQueryRepository.class);
    private final DashboardScopeResolver resolver = new DashboardScopeResolver(queryRepository);

    private static CurrentUser user(Long orgId, String... roles) {
        return new CurrentUser(
                1L,
                "user",
                "用户",
                "user@example.com",
                orgId,
                java.util.Arrays.stream(roles)
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );
    }

    @Test
    @DisplayName("org 类型 admin 或 ROLE_SYSTEM_ADMIN 角色 → 全量；academic → 本院；functional → 本部门")
    void resolveShouldMapOrgTypeToScope() {
        when(queryRepository.findOrgTypeById(35L)).thenReturn("admin");
        when(queryRepository.findOrgTypeById(57L)).thenReturn("academic");
        when(queryRepository.findOrgTypeById(66L)).thenReturn("functional");

        assertEquals(DashboardScope.full(), resolver.resolve(user(35L, "ROLE_REPORTER")));
        assertEquals(DashboardScope.full(), resolver.resolve(user(57L, "ROLE_SYSTEM_ADMIN")));
        assertEquals(DashboardScope.college(57L), resolver.resolve(user(57L, "ROLE_REPORTER")));
        assertEquals(DashboardScope.functional(66L), resolver.resolve(user(66L, "ROLE_REPORTER")));
    }

    @Test
    @DisplayName("无 org/查不到组织的用户维持全量（不收缩）；null 用户全量")
    void resolveShouldFallbackToFullWhenOrgUnknown() {
        when(queryRepository.findOrgTypeById(99L)).thenReturn(null);

        assertEquals(DashboardScope.full(), resolver.resolve(user(null, "ROLE_REPORTER")));
        assertEquals(DashboardScope.full(), resolver.resolve(user(99L, "ROLE_REPORTER")));
        assertEquals(DashboardScope.full(), resolver.resolve(null));

        verify(queryRepository).findOrgTypeById(99L);
    }
}
