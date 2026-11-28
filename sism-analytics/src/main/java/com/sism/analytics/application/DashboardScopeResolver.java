package com.sism.analytics.application;

import com.sism.analytics.domain.repository.DashboardScopeQueryRepository;
import com.sism.shared.application.dto.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * DashboardScopeResolver - 从 security 上下文解析看板数据视角。
 *
 * <p>2026-11-27 角色分流（用户拍板）：appraisal-summary 等看板端点原先对任何登录角色
 * 都返回全校全量，学院填报人（计算机学院）看到的是全校 561 项/28 部门维度。
 * 解析规则：</p>
 * <ul>
 *   <li>带 ROLE_SYSTEM_ADMIN 角色 → 全量；</li>
 *   <li>org 35 战略发展部（sys_org.type='admin'）→ 全量；</li>
 *   <li>sys_org.type='academic' → 学院视角（只统计本院）；</li>
 *   <li>sys_org.type='functional' → 职能部门视角（只统计本部门）；</li>
 *   <li>无 org 或组织查不到 → 维持全量（无法定位归属时不做收缩，与既有行为一致）。</li>
 * </ul>
 */
@Slf4j
@Service
public class DashboardScopeResolver {

    private static final String ADMIN_ROLE = "ROLE_SYSTEM_ADMIN";

    private final DashboardScopeQueryRepository dashboardScopeQueryRepository;

    public DashboardScopeResolver(DashboardScopeQueryRepository dashboardScopeQueryRepository) {
        this.dashboardScopeQueryRepository = dashboardScopeQueryRepository;
    }

    public DashboardScope resolve(CurrentUser currentUser) {
        if (currentUser == null) {
            return DashboardScope.full();
        }
        if (hasAdminRole(currentUser)) {
            return DashboardScope.full();
        }
        Long orgId = currentUser.getOrgId();
        if (orgId == null) {
            return DashboardScope.full();
        }
        String orgType = dashboardScopeQueryRepository.findOrgTypeById(orgId);
        if (orgType == null) {
            log.warn("Dashboard scope: current user org {} not found, falling back to FULL", orgId);
            return DashboardScope.full();
        }
        return switch (orgType) {
            case "academic" -> DashboardScope.college(orgId);
            case "functional" -> DashboardScope.functional(orgId);
            // admin（校级）组织 → 全量
            default -> DashboardScope.full();
        };
    }

    private static boolean hasAdminRole(CurrentUser currentUser) {
        for (GrantedAuthority authority : currentUser.getAuthorities()) {
            if (ADMIN_ROLE.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
