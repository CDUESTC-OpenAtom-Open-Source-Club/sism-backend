package com.sism.iam.application.service;

import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UsernamePolicy;
import com.sism.iam.domain.access.Role;
import com.sism.iam.domain.access.RoleRepository;
import com.sism.iam.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * UserService - 用户服务
 * 处理用户管理相关业务逻辑
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 创建用户
     */
    @Transactional
    public User createUser(
            String username,
            String password,
            String realName,
            String email,
            String phone,
            Long orgId,
            List<String> roleCodes
    ) {
        UsernamePolicy.validate(username);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRealName(realName);
        applyContactInfo(user, email, phone, null);
        user.setOrgId(orgId);
        user.setIsActive(true);
        user.setRoles(resolveRoles(roleCodes));

        return userRepository.save(user);
    }

    /**
     * 更新用户
     */
    @Transactional
    public User updateUser(Long userId, String realName, String email, String phone, Long orgId, List<String> roleCodes) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (realName != null) {
            user.setRealName(realName);
        }
        applyContactInfo(user, email, phone, userId);
        if (orgId != null) {
            user.setOrgId(orgId);
        }
        if (roleCodes != null) {
            user.setRoles(resolveRoles(roleCodes));
        }

        return userRepository.save(user);
    }

    @Transactional
    public User updateCurrentUserContact(Long userId, String email, String phone) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        applyContactInfo(user, email, phone, userId);
        return userRepository.save(user);
    }

    /**
     * 删除用户（逻辑删除）
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setIsActive(false);
        userRepository.save(user);
    }

    /**
     * 根据ID查询用户
     */
    public Optional<User> findById(Long userId) {
        return userRepository.findById(userId);
    }

    /**
     * 根据用户名查询用户
     */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * 根据组织ID查询用户
     */
    public List<User> findByOrgId(Long orgId) {
        return userRepository.findByOrgId(orgId);
    }

    /**
     * 查询所有用户
     */
    public List<User> findAll() {
        return userRepository.findAll();
    }

    /**
     * 分页查询所有用户
     */
    @Transactional(readOnly = true)
    public Page<User> findPage(int page, int size) {
        return userRepository.findAll(PaginationPolicy.toPageRequest(page, size));
    }

    /**
     * 锁定用户
     */
    @Transactional
    public void lockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setIsActive(false);
        userRepository.save(user);
    }

    /**
     * 解锁用户
     */
    @Transactional
    public void unlockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setIsActive(true);
        userRepository.save(user);
    }

    private Set<Role> resolveRoles(List<String> roleCodes) {
        Set<Role> roles = new LinkedHashSet<>();
        if (roleCodes == null) {
            return roles;
        }

        for (String roleCode : roleCodes) {
            if (roleCode == null || roleCode.isBlank()) {
                continue;
            }

            Role role = roleRepository.findByRoleCode(roleCode.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));
            roles.add(role);
        }

        return roles;
    }

    private void applyContactInfo(User user, String email, String phone, Long selfUserId) {
        String normalizedEmail = ContactInfoPolicy.normalizeEmail(email);
        String normalizedPhone = ContactInfoPolicy.normalizePhone(phone);

        if (normalizedEmail != null) {
            userRepository.findByEmail(normalizedEmail)
                    .filter(existing -> !existing.getId().equals(selfUserId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("邮箱已被其他用户使用");
                    });
        }

        if (normalizedPhone != null) {
            userRepository.findByPhone(normalizedPhone)
                    .filter(existing -> !existing.getId().equals(selfUserId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("手机号已被其他用户使用");
                    });
        }

        user.setEmail(normalizedEmail);
        user.setPhone(normalizedPhone);
    }
}
