package com.sism.iam.infrastructure.persistence;

import com.sism.iam.domain.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaUserRepositoryInternal extends JpaRepository<User, Long> {
    @Override
    @EntityGraph(attributePaths = "roles")
    Optional<User> findById(Long id);

    @EntityGraph(attributePaths = "roles")
    List<User> findByIdIn(List<Long> ids);

    @Override
    @EntityGraph(attributePaths = "roles")
    Page<User> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByUsername(String username);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByPhone(String phone);

    @EntityGraph(attributePaths = "roles")
    List<User> findByOrgId(Long orgId);

    @Query("SELECT DISTINCT u FROM User u JOIN FETCH u.roles allRoles JOIN u.roles filterRole WHERE filterRole.id = :roleId")
    List<User> findByRoleId(Long roleId);

    @Query(value = "SELECT role_id FROM sys_user_role WHERE user_id = :userId ORDER BY role_id", nativeQuery = true)
    List<Long> findRoleIdsByUserId(Long userId);

    @Query(value = """
            SELECT r.role_code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = :userId
            ORDER BY ur.role_id
            """, nativeQuery = true)
    List<String> findRoleCodesByUserId(Long userId);

    /**
     * 有效权限码：权限 = 角色 + 组织作用域（2026-10-26 权限模型改造）。
     * - ALL：全组织有效
     * - OWN_ORG：角色在用户所属组织有效（角色绑定在用户上，恒有效）
     * - FIXED_ORG：仅当 scope_org_id = 用户 org_id 时有效
     */
    @Query(value = """
            SELECT DISTINCT p.perm_code
            FROM sys_user_role ur
            JOIN sys_user u ON u.id = ur.user_id
            JOIN sys_role r ON r.id = ur.role_id
            JOIN sys_role_permission rp ON rp.role_id = r.id
            JOIN sys_permission p ON p.id = rp.perm_id
            WHERE ur.user_id = :userId
              AND COALESCE(p.is_enabled, true) = true
              AND (
                    r.data_access_mode = 'ALL'
                 OR r.data_access_mode = 'OWN_ORG'
                 OR (r.data_access_mode = 'FIXED_ORG' AND r.scope_org_id = u.org_id)
              )
            ORDER BY p.perm_code
            """, nativeQuery = true)
    List<String> findPermissionCodesByUserId(Long userId);

    /**
     * 有效权限明细（含来源角色与作用域是否有效），用于 /auth/permissions/effective 核对接口。
     * 列顺序：perm_code, role_code, data_access_mode, scope_org_id, scope_valid
     */
    @Query(value = """
            SELECT p.perm_code,
                   r.role_code,
                   r.data_access_mode,
                   r.scope_org_id,
                   (r.data_access_mode = 'ALL'
                     OR r.data_access_mode = 'OWN_ORG'
                     OR (r.data_access_mode = 'FIXED_ORG' AND r.scope_org_id = u.org_id)) AS scope_valid
            FROM sys_user_role ur
            JOIN sys_user u ON u.id = ur.user_id
            JOIN sys_role r ON r.id = ur.role_id
            JOIN sys_role_permission rp ON rp.role_id = r.id
            JOIN sys_permission p ON p.id = rp.perm_id
            WHERE ur.user_id = :userId
              AND COALESCE(p.is_enabled, true) = true
            ORDER BY r.role_code, p.perm_code
            """, nativeQuery = true)
    List<Object[]> findEffectivePermissionRows(Long userId);

    @Query("""
            SELECT r.id, COUNT(DISTINCT u.id)
            FROM User u
            JOIN u.roles r
            WHERE r.id IN :roleIds
            GROUP BY r.id
            """)
    List<Object[]> countUsersByRoleIds(List<Long> roleIds);

    @EntityGraph(attributePaths = "roles")
    List<User> findByIsActive(Boolean isActive);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
}
