package com.sism.analytics.application;

/**
 * DashboardScope - 数据看板端点的数据视角（2026-11-27 角色分流）。
 *
 * <p>背景：appraisal-summary 等看板端点原先对任何登录角色都返回全校全量，
 * 学院填报人会看到全校 561 项/28 个部门维度。本对象由
 * {@link DashboardScopeResolver} 从 security 上下文解析（org 类型 + admin 角色），
 * service 层据此把统计限定在当前用户自己的组织内：</p>
 * <ul>
 *   <li>{@link Kind#FULL}：org 35 战略发展部（sys_org.type='admin'）或
 *       ROLE_SYSTEM_ADMIN 角色 → 全量（与既有行为完全一致）</li>
 *   <li>{@link Kind#COLLEGE}：学院组织（sys_org.type='academic'）→ 只统计本院</li>
 *   <li>{@link Kind#FUNCTIONAL}：职能组织（sys_org.type='functional'）→ 只统计本部门</li>
 * </ul>
 */
public record DashboardScope(Kind kind, Long orgId) {

    public enum Kind {
        FULL,
        /** 学院视角：orgId = 本院组织 ID */
        COLLEGE,
        /** 职能部门视角：orgId = 本部门组织 ID */
        FUNCTIONAL
    }

    public static DashboardScope full() {
        return new DashboardScope(Kind.FULL, null);
    }

    public static DashboardScope college(long orgId) {
        return new DashboardScope(Kind.COLLEGE, orgId);
    }

    public static DashboardScope functional(long orgId) {
        return new DashboardScope(Kind.FUNCTIONAL, orgId);
    }

    public boolean isFull() {
        return kind == Kind.FULL;
    }
}
