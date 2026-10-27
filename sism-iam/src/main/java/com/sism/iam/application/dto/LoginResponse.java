package com.sism.iam.application.dto;

import com.sism.iam.domain.user.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LoginResponse - 登录响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
    private String type = "Bearer";
    private String tokenType = "Bearer";
    private Long userId;
    private String username;
    private String realName;
    private UserInfo user;

    public static LoginResponse fromUser(User user, String accessToken, String refreshToken, long expiresIn) {
        return fromUser(
                user,
                user.getRoles().stream().map(role -> role.getRoleCode()).toList(),
                null,
                null,
                List.of(),
                accessToken,
                refreshToken,
                expiresIn
        );
    }

    public static LoginResponse fromUser(
            User user,
            List<String> roleCodes,
            String orgName,
            String orgType,
            List<String> permissions,
            String accessToken,
            String refreshToken,
            long expiresIn
    ) {
        LoginResponse response = new LoginResponse();
        response.setToken(accessToken);
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setExpiresIn(expiresIn);
        response.setType("Bearer");
        response.setTokenType("Bearer");
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRealName(user.getRealName());
        response.setUser(new UserInfo(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getEmail(),
                user.getPhone(),
                user.getOrgId(),
                orgName,
                orgType,
                roleCodes,
                permissions
        ));
        return response;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String username;
        private String realName;
        private String email;
        private String phone;
        private Long orgId;
        private String orgName;
        private String orgType;
        private List<String> roles;
        /** 有效权限码：后端按「角色 + 组织作用域」计算后下发，前端只消费、不再硬编码 */
        private List<String> permissions;
    }
}
