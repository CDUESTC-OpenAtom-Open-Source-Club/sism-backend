package com.sism.iam.interfaces.rest;

import com.sism.iam.application.dto.PasswordResetConfirmRequest;
import com.sism.iam.application.dto.PasswordResetRequest;
import com.sism.iam.application.dto.PasswordResetVerifyRequest;
import com.sism.iam.application.dto.UpdateContactRequest;
import com.sism.iam.application.service.AuthService;
import com.sism.iam.application.service.ContactInfoPolicy;
import com.sism.iam.application.service.PasswordResetService;
import com.sism.iam.application.service.UserService;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.iam.application.dto.LoginRequest;
import com.sism.iam.application.dto.LoginResponse;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UsernamePolicy;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AuthController - 认证API控制器
 * 提供用户认证相关的REST API端点
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
@Tag(name = "认证管理", description = "用户认证相关接口")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final OrganizationRepository organizationRepository;
    private final PasswordResetService passwordResetService;

    /**
     * 用户登录
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/users/me/contact")
    @Operation(summary = "更新当前用户联系信息")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> updateMyContact(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody UpdateContactRequest request) {
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }
        try {
            User user = userService.updateCurrentUserContact(currentUser.getId(), request.getEmail(), request.getPhone());
            return ResponseEntity.ok(ApiResponse.success(toUserSummaryResponse(user)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(1001, ex.getMessage()));
        }
    }

    @PostMapping("/password-reset/send")
    @Operation(summary = "发送密码找回验证码")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendPasswordResetCode(
            @Valid @RequestBody PasswordResetRequest request,
            HttpServletRequest httpServletRequest) {
        try {
            PasswordResetService.SendResult result = passwordResetService.sendResetCode(
                    request.getEmail(),
                    extractIpAddress(httpServletRequest),
                    httpServletRequest.getHeader("User-Agent")
            );
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "message", result.getMessage(),
                    "expiresIn", result.getExpiresIn()
            )));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, ex.getMessage()));
        } catch (IllegalStateException ex) {
            HttpStatus status = ex.getMessage() != null && ex.getMessage().contains("邮件发送失败")
                    ? HttpStatus.INTERNAL_SERVER_ERROR
                    : HttpStatus.TOO_MANY_REQUESTS;
            int code = status == HttpStatus.INTERNAL_SERVER_ERROR ? 500 : 429;
            return ResponseEntity.status(status).body(ApiResponse.error(code, ex.getMessage()));
        }
    }

    @PostMapping("/password-reset/verify")
    @Operation(summary = "校验密码找回验证码")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyPasswordResetCode(
            @Valid @RequestBody PasswordResetVerifyRequest request) {
        try {
            passwordResetService.verifyCode(request.getEmail(), request.getCode());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "valid", true,
                    "message", "验证码正确"
            )));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, ex.getMessage()));
        }
    }

    @PostMapping("/password-reset/confirm")
    @Operation(summary = "确认密码重置")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request,
            HttpServletRequest httpServletRequest) {
        try {
            passwordResetService.resetPassword(
                    request.getEmail(),
                    request.getCode(),
                    request.getNewPassword(),
                    extractIpAddress(httpServletRequest),
                    httpServletRequest.getHeader("User-Agent")
            );
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "message", "密码重置成功，请使用新密码登录"
            )));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, ex.getMessage()));
        }
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> register(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody RegisterRequest request) {
        ResponseEntity<ApiResponse<UserSummaryResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        User user = authService.register(
                request.getUsername(),
                request.getPassword(),
                request.getRealName()
        );
        return ResponseEntity.ok(ApiResponse.success(UserSummaryResponse.fromUser(user)));
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> getCurrentUser(
            @AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }
        return userService.findById(currentUser.getId())
                .map(user -> ResponseEntity.ok(ApiResponse.success(toUserSummaryResponse(user))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取当前用户权限编码列表
     */
    @GetMapping("/permissions")
    @Operation(summary = "获取当前用户权限编码列表")
    public ResponseEntity<ApiResponse<List<String>>> getCurrentUserPermissions(
            @AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.getPermissionCodes(currentUser.getId())));
    }

    /**
     * 验证Token
     */
    @GetMapping("/validate")
    @Operation(summary = "验证Token")
    public ResponseEntity<ApiResponse<Boolean>> validateToken(@RequestHeader("Authorization") String token) {
        boolean valid = authService.validateToken(token.replace("Bearer ", ""));
        return ResponseEntity.ok(ApiResponse.success(valid));
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    @Operation(summary = "用户登出")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring(7));
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 健康检查端点
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", "UP",
                "timestamp", java.time.Instant.now().toString()
        )));
    }

    /**
     * 查询所有用户
     */
    @GetMapping("/users")
    @Operation(summary = "查询所有用户")
    public ResponseEntity<ApiResponse<UserListPageResponse>> getAllUsers(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ResponseEntity<ApiResponse<UserListPageResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        Page<User> userPage = userService.findPage(page, size);
        Page<UserListItemResponse> responsePage = userPage.map(this::toUserListItemResponse);
        return ResponseEntity.ok(ApiResponse.success(UserListPageResponse.fromPage(responsePage)));
    }

    /**
     * 根据ID查询用户
     */
    @GetMapping("/users/{id}")
    @Operation(summary = "根据ID查询用户")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> getUserById(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id) {
        ResponseEntity<ApiResponse<UserSummaryResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        return userService.findById(id)
                .map(user -> ResponseEntity.ok(ApiResponse.success(toUserSummaryResponse(user))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 根据用户名查询用户
     */
    @GetMapping("/users/username/{username}")
    @Operation(summary = "根据用户名查询用户")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> getUserByUsername(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable String username) {
        ResponseEntity<ApiResponse<UserSummaryResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        return userService.findByUsername(username)
                .map(user -> ResponseEntity.ok(ApiResponse.success(toUserSummaryResponse(user))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 根据组织ID查询用户
     */
    @GetMapping("/users/org/{orgId}")
    @Operation(summary = "根据组织ID查询用户")
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getUsersByOrgId(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long orgId) {
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }
        return ResponseEntity.ok(ApiResponse.success(
                userService.findByOrgId(orgId).stream().map(this::toUserSummaryResponse).collect(Collectors.toList())
        ));
    }

    /**
     * 创建用户
     */
    @PostMapping("/users")
    @Operation(summary = "创建用户")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> createUser(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateUserRequest request) {
        ResponseEntity<ApiResponse<UserSummaryResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        User user = userService.createUser(
                request.getUsername(),
                request.getPassword(),
                request.getRealName(),
                request.getEmail(),
                request.getPhone(),
                request.getOrgId(),
                request.getRoles()
        );
        return ResponseEntity.ok(ApiResponse.success(toUserSummaryResponse(user)));
    }

    /**
     * 更新用户
     */
    @PutMapping("/users/{id}")
    @Operation(summary = "更新用户")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> updateUser(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        ResponseEntity<ApiResponse<UserSummaryResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        User user = userService.updateUser(
                id,
                request.getRealName(),
                request.getEmail(),
                request.getPhone(),
                request.getOrgId(),
                request.getRoles()
        );
        return ResponseEntity.ok(ApiResponse.success(toUserSummaryResponse(user)));
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/users/{id}")
    @Operation(summary = "删除用户")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id) {
        ResponseEntity<ApiResponse<Void>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 锁定用户
     */
    @PostMapping("/users/{id}/lock")
    @Operation(summary = "锁定用户")
    public ResponseEntity<ApiResponse<Void>> lockUser(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id) {
        ResponseEntity<ApiResponse<Void>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        userService.lockUser(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 解锁用户
     */
    @PostMapping("/users/{id}/unlock")
    @Operation(summary = "解锁用户")
    public ResponseEntity<ApiResponse<Void>> unlockUser(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id) {
        ResponseEntity<ApiResponse<Void>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        userService.unlockUser(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private boolean hasAdminOrgAccess(CurrentUser currentUser) {
        if (currentUser == null || currentUser.getOrgId() == null) {
            return false;
        }

        return organizationRepository.findById(currentUser.getOrgId())
                .map(org -> org.getType() == OrgType.admin)
                .orElse(false);
    }

    private <T> ResponseEntity<ApiResponse<T>> denyIfNoAdminOrgAccess(CurrentUser currentUser) {
        if (hasAdminOrgAccess(currentUser)) {
            return null;
        }

        return ResponseEntity.status(403).body(ApiResponse.error(403, "无权限访问"));
    }

    private SysOrg findOrganization(Long orgId) {
        if (orgId == null) {
            return null;
        }
        return organizationRepository.findById(orgId).orElse(null);
    }

    private UserSummaryResponse toUserSummaryResponse(User user) {
        var organization = findOrganization(user.getOrgId());
        return UserSummaryResponse.fromUser(
                user,
                organization != null ? organization.getName() : null,
                organization != null ? organization.getType().name() : null
        );
    }

    // ==================== Request DTOs ====================

    @lombok.Data
    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = UsernamePolicy.MIN_LENGTH, max = UsernamePolicy.MAX_LENGTH, message = "用户名长度需为3-20个字符")
        @Pattern(regexp = UsernamePolicy.REGEX, message = "用户名只能包含字母、数字和下划线")
        private String username;

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须在8到128个字符之间")
        private String password;

        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名长度不能超过64个字符")
        private String realName;
    }

    @lombok.Data
    public static class CreateUserRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = UsernamePolicy.MIN_LENGTH, max = UsernamePolicy.MAX_LENGTH, message = "用户名长度需为3-20个字符")
        @Pattern(regexp = UsernamePolicy.REGEX, message = "用户名只能包含字母、数字和下划线")
        private String username;
        private String password;
        private String realName;
        @Pattern(regexp = "^$|" + ContactInfoPolicy.EMAIL_REGEX, message = "邮箱格式不正确")
        private String email;
        @Pattern(regexp = "^$|" + ContactInfoPolicy.PHONE_REGEX, message = "手机号格式不正确")
        private String phone;
        private Long orgId;
        private List<String> roles;
    }

    @lombok.Data
    public static class UpdateUserRequest {
        private String realName;
        @Pattern(regexp = "^$|" + ContactInfoPolicy.EMAIL_REGEX, message = "邮箱格式不正确")
        private String email;
        @Pattern(regexp = "^$|" + ContactInfoPolicy.PHONE_REGEX, message = "手机号格式不正确")
        private String phone;
        private Long orgId;
        private List<String> roles;
    }

    @lombok.Data
    public static class RefreshTokenRequest {
        @NotBlank(message = "刷新令牌不能为空")
        private String refreshToken;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UserSummaryResponse {
        private Long id;
        private String username;
        private String realName;
        private String email;
        private String phone;
        private Long orgId;
        private String orgName;
        private String orgType;
        private Boolean isActive;
        private List<String> roles;

        public static UserSummaryResponse fromUser(User user) {
            return fromUser(user, null, null);
        }

        public static UserSummaryResponse fromUser(User user, String orgName, String orgType) {
            return new UserSummaryResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getRealName(),
                    user.getEmail(),
                    user.getPhone(),
                    user.getOrgId(),
                    orgName,
                    orgType,
                    user.getIsActive(),
                    user.getRoles() == null
                            ? List.of()
                            : user.getRoles().stream().map(role -> role.getRoleCode()).collect(Collectors.toList())
            );
        }
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UserRoleItemResponse {
        private String roleCode;
        private String roleName;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UserListItemResponse {
        private Long userId;
        private String username;
        private String realName;
        private String email;
        private String phone;
        private Long orgId;
        private String orgName;
        private List<UserRoleItemResponse> roles;
        private String status;
        private String lastLoginAt;
        private String createdAt;
        private String updatedAt;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UserListPageResponse {
        private List<UserListItemResponse> content;
        private long totalElements;
        private int totalPages;
        private int number;
        private int size;

        public static UserListPageResponse fromPage(org.springframework.data.domain.Page<UserListItemResponse> page) {
            return new UserListPageResponse(
                    new ArrayList<>(page.getContent()),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.getNumber(),
                    page.getSize()
            );
        }
    }

    private UserListItemResponse toUserListItemResponse(User user) {
        List<UserRoleItemResponse> roles = (user.getRoles() == null ? List.<com.sism.iam.domain.access.Role>of() : user.getRoles()).stream()
                .map(role -> new UserRoleItemResponse(role.getRoleCode(), role.getRoleName()))
                .toList();
        var organization = findOrganization(user.getOrgId());

        return new UserListItemResponse(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getEmail(),
                user.getPhone(),
                user.getOrgId(),
                organization != null ? organization.getName() : null,
                roles,
                Boolean.TRUE.equals(user.getIsActive()) ? "active" : "disabled",
                null,
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : null,
                user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : null
        );
    }

    /**
     * 刷新访问令牌
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新访问令牌")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        try {
            Map<String, Object> result = authService.refreshToken(request.getRefreshToken());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "刷新令牌无效或已过期"));
        }
    }

    private String extractIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
