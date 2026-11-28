package com.sism.analytics.domain.repository;

/**
 * DashboardScopeQueryRepository - 看板视角解析所需的组织类型查询。
 *
 * <p>2026-11-27 角色分流：按当前用户 orgId 查 sys_org.type
 * （admin/functional/academic），供 {@code DashboardScopeResolver} 决定
 * 全量/本院/本部门三种看板视角。analytics 模块不依赖 organization 上下文，
 * 走原生 SQL 查询（与模块内其他查询仓库同构）。</p>
 */
public interface DashboardScopeQueryRepository {

    /**
     * 查询组织类型（sys_org.type 文本值：admin/functional/academic）。
     *
     * @return 组织类型；组织不存在或已删除返回 null
     */
    String findOrgTypeById(Long orgId);

    /**
     * 查询职能部门「有下发关联」的学院集合（2026-11-27 拍板）：
     * 学院侧存在归口=本部门的线（学院线指标 → sys_task → plan.functional_org_id=本部门）
     * 的学院。供 college-levels 系列端点在职能部门视角下收敛学院列表——
     * 职能部门只能看到自己已经下发指标关联的学院。
     *
     * @param functionalOrgId 职能部门组织 ID
     * @return 关联学院组织 ID 集合（去重；无关联返回空集合）
     */
    java.util.List<Long> findLinkedCollegeIds(Long functionalOrgId);
}
