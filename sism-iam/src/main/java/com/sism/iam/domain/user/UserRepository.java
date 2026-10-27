package com.sism.iam.domain.user;

import com.sism.shared.domain.user.UserProvider;
import com.sism.shared.domain.user.UserIdentity;
import com.sism.iam.domain.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * UserRepository - 用户仓储接口
 * 定义在领域层，由基础设施层实现
 */
public interface UserRepository extends UserProvider {

    /**
     * 根据ID查询用户
     */
    Optional<User> findById(Long id);

    default List<User> findAllByIds(List<Long> ids) {
        return List.of();
    }

    /**
     * 查询所有用户
     */
    List<User> findAll();

    /**
     * 分页查询所有用户
     */
    Page<User> findAll(Pageable pageable);

    /**
     * 根据用户名查询用户
     */
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    /**
     * 根据组织ID查询用户
     */
    List<User> findByOrgId(Long orgId);

    /**
     * 根据角色ID查询用户
     */
    List<User> findByRoleId(Long roleId);

    /**
     * 根据用户ID查询角色ID列表
     */
    List<Long> findRoleIdsByUserId(Long userId);

    @Override
    default List<Long> getUserRoleIds(Long userId) {
        return findRoleIdsByUserId(userId);
    }

    /**
     * 根据用户ID查询角色编码列表
     */
    List<String> findRoleCodesByUserId(Long userId);

    /**
     * 根据用户ID查询权限编码列表
     */
    default List<String> findPermissionCodesByUserId(Long userId) {
        return List.of();
    }

    /**
     * 有效权限明细：perm_code, role_code, data_access_mode, scope_org_id, scope_valid
     */
    default List<Object[]> findEffectivePermissionRows(Long userId) {
        return List.of();
    }

    @Override
    default List<String> getUserPermissionCodes(Long userId) {
        return findPermissionCodesByUserId(userId);
    }

    @Override
    default Optional<UserIdentity> findIdentity(Long userId) {
        return findById(userId).map(user -> new UserIdentity(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getOrgId(),
                user.getIsActive(),
                Boolean.TRUE.equals(user.getIsDemo())
        ));
    }

    @Override
    default List<UserIdentity> findActiveIdentitiesByRole(Long roleId) {
        return findByRoleId(roleId).stream()
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                .map(user -> new UserIdentity(
                        user.getId(),
                        user.getUsername(),
                        user.getRealName(),
                        user.getOrgId(),
                        user.getIsActive(),
                        Boolean.TRUE.equals(user.getIsDemo())
                ))
                .toList();
    }

    @Override
    default Optional<Long> getUserOrgId(Long userId) {
        return findIdentity(userId).map(UserIdentity::orgId);
    }

    /**
     * 批量统计角色关联用户数
     */
    default Map<Long, Long> countUsersByRoleIds(Set<Long> roleIds) {
        return Map.of();
    }

    /**
     * 根据激活状态查询用户
     */
    List<User> findByIsActive(Boolean isActive);

    /**
     * 保存用户
     */
    User save(User user);

    /**
     * 删除用户
     */
    void delete(User user);

    /**
     * 检查用户是否存在
     */
    boolean existsById(Long id);

    /**
     * 检查用户名是否存在
     */
    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
