package com.sism.iam.application.service;

import com.sism.iam.application.JwtTokenService;
import com.sism.iam.application.dto.LoginRequest;
import com.sism.iam.application.dto.LoginResponse;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import com.sism.iam.domain.user.UsernamePolicy;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.shared.domain.exception.AuthenticationException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * AuthService - 认证服务
 * 处理用户登录、注册等认证逻辑
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final OrganizationRepository organizationRepository;

    /**
     * 用户登录
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String account = ContactInfoPolicy.normalizeAccount(request.getAccount());
        if (account == null) {
            throw new IllegalArgumentException("请输入账号");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("请输入密码");
        }

        String clientKey = "global";
        loginAttemptService.assertNotBlocked(account, clientKey);

        User user = findUserByAccount(account)
                .orElseThrow(() -> new AuthenticationException("INVALID_CREDENTIALS", "用户名或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.recordFailure(account, clientKey);
            throw new AuthenticationException("INVALID_CREDENTIALS", "用户名或密码错误");
        }

        if (!user.getIsActive()) {
            throw new AuthenticationException("USER_DISABLED", "账号已被禁用，请联系管理员");
        }

        loginAttemptService.recordSuccess(account, clientKey);

        List<String> roleCodes = userRepository.findRoleCodesByUserId(user.getId());

        String accessToken = jwtTokenService.generateToken(user, roleCodes);
        String refreshToken = jwtTokenService.generateRefreshToken(user, roleCodes);
        var organization = organizationRepository.findById(user.getOrgId()).orElse(null);

        return LoginResponse.fromUser(
                user,
                roleCodes,
                organization != null ? organization.getName() : null,
                organization != null ? organization.getType().name() : null,
                accessToken,
                refreshToken,
                jwtTokenService.getExpirationSeconds()
        );
    }

    /**
     * 用户注册
     */
    @Transactional
    public User register(String username, String password, String realName) {
        UsernamePolicy.validate(username);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("请输入密码");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRealName(realName);
        user.setIsActive(true);

        return userRepository.save(user);
    }

    /**
     * 验证Token
     */
    public boolean validateToken(String token) {
        return jwtTokenService.validateToken(token);
    }

    public void logout(String token) {
        jwtTokenService.blacklistToken(token);
    }

    /**
     * 获取当前用户ID
     */
    public Long getUserIdFromToken(String token) {
        return jwtTokenService.getUserIdFromToken(token);
    }

    /**
     * 刷新访问令牌
     */
    public Map<String, Object> refreshToken(String refreshToken) {
        return jwtTokenService.refreshToken(refreshToken);
    }

    /**
     * 获取当前用户权限编码列表
     */
    @Transactional(readOnly = true)
    public List<String> getPermissionCodes(Long userId) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return userRepository.findPermissionCodesByUserId(userId);
    }

    private java.util.Optional<User> findUserByAccount(String account) {
        if (ContactInfoPolicy.looksLikeEmail(account)) {
            return userRepository.findByEmail(account);
        }
        if (ContactInfoPolicy.looksLikePhone(account)) {
            return userRepository.findByPhone(account);
        }
        return userRepository.findByUsername(account);
    }
}
