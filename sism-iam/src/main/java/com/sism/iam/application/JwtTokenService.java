package com.sism.iam.application;

import com.sism.iam.domain.user.User;
import com.sism.iam.domain.access.Role;
import com.sism.iam.domain.user.UserRepository;
import com.sism.util.TokenBlacklistService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class JwtTokenService {

    @Value("${app.jwt.secret:${jwt.secret:}}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private Long expiration;

    @Value("${jwt.refresh-expiration:604800000}")
    private Long refreshExpiration;

    private final TokenBlacklistService tokenBlacklistService;
    private final UserRepository userRepository;

    @Autowired
    public JwtTokenService(TokenBlacklistService tokenBlacklistService, UserRepository userRepository) {
        this.tokenBlacklistService = tokenBlacklistService;
        this.userRepository = userRepository;
    }

    public JwtTokenService(TokenBlacklistService tokenBlacklistService) {
        this(tokenBlacklistService, null);
    }

    public JwtTokenService() {
        this.tokenBlacklistService = null;
        this.userRepository = null;
    }

    @PostConstruct
    void validateConfiguration() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must be configured");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 256 bits (32 bytes)");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        return generateToken(user, extractRoleCodes(user.getRoles()));
    }

    public String generateToken(User user, List<String> roleCodes) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());
        claims.put("orgId", user.getOrgId());
        claims.put("roles", normalizeRoleCodes(roleCodes));
        claims.put("tokenVersion", normalizeTokenVersion(user));

        return Jwts.builder()
                .claims(claims)
                .subject(user.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            return !isTokenExpired(token) && !isBlacklisted(token) && isTokenVersionCurrent(token);
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserIdFromToken(String token) {
        return extractClaims(token).get("userId", Long.class);
    }

    public Long getOrgIdFromToken(String token) {
        return extractClaims(token).get("orgId", Long.class);
    }

    public List<String> getRolesFromToken(String token) {
        Object rawRoles = extractClaims(token).get("roles");
        if (rawRoles instanceof Collection<?> roles) {
            return roles.stream()
                    .map(String::valueOf)
                    .filter(role -> !role.isBlank())
                    .toList();
        }
        return List.of();
    }

    public String getEmailFromToken(String token) {
        return extractClaims(token).get("email", String.class);
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private boolean isTokenExpired(String token) {
        return extractClaims(token).getExpiration().before(new Date());
    }

    public Map<String, Object> refreshToken(String refreshToken) {
        Claims claims = parseRefreshTokenClaims(refreshToken);
        if (!"refresh".equalsIgnoreCase(claims.get("type", String.class))) {
            throw new IllegalArgumentException("Not a refresh token");
        }

        if (!validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        String username = extractUsername(refreshToken);
        Long userId = getUserIdFromToken(refreshToken);
        Long orgId = getOrgIdFromToken(refreshToken);
        List<String> roleCodes = getRolesFromToken(refreshToken);

        blacklistToken(refreshToken);

        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setOrgId(orgId);

        String newAccessToken = generateToken(user, roleCodes);
        String newRefreshToken = generateRefreshToken(user, roleCodes);

        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", newAccessToken);
        result.put("refreshToken", newRefreshToken);
        result.put("expiresIn", expiration / 1000);
        result.put("tokenType", "Bearer");

        return result;
    }

    public String generateRefreshToken(User user) {
        return generateRefreshToken(user, extractRoleCodes(user.getRoles()));
    }

    public String generateRefreshToken(User user, List<String> roleCodes) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());
        claims.put("orgId", user.getOrgId());
        claims.put("roles", normalizeRoleCodes(roleCodes));
        claims.put("tokenVersion", normalizeTokenVersion(user));
        claims.put("type", "refresh");

        return Jwts.builder()
                .claims(claims)
                .subject(user.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(getSigningKey())
                .compact();
    }

    public long getExpirationSeconds() {
        return expiration / 1000;
    }

    public void blacklistToken(String token) {
        if (token != null && !token.isBlank()) {
            if (tokenBlacklistService != null) {
                tokenBlacklistService.blacklist(token);
            }
        }
    }

    private boolean isBlacklisted(String token) {
        return tokenBlacklistService != null && tokenBlacklistService.isBlacklisted(token);
    }

    private boolean isRefreshToken(String token) {
        return "refresh".equalsIgnoreCase(extractClaims(token).get("type", String.class));
    }

    private Claims parseRefreshTokenClaims(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token is required");
        }

        try {
            return extractClaims(refreshToken);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid refresh token", ex);
        }
    }

    private boolean isTokenVersionCurrent(String token) {
        if (userRepository == null) {
            return true;
        }

        Claims claims = extractClaims(token);
        String username = claims.getSubject();
        if (username == null || username.isBlank()) {
            return false;
        }

        Long tokenVersion = claims.get("tokenVersion", Long.class);
        long expectedVersion = tokenVersion == null ? 0L : tokenVersion;

        return userRepository.findByUsername(username)
                .map(user -> normalizeTokenVersion(user) == expectedVersion)
                .orElse(false);
    }

    private List<String> extractRoleCodes(Collection<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }

        return normalizeRoleCodes(roles.stream()
                .map(Role::getRoleCode)
                .toList());
    }

    private List<String> normalizeRoleCodes(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }

        return roleCodes.stream()
                .filter(roleCode -> roleCode != null && !roleCode.isBlank())
                .distinct()
                .toList();
    }

    private long normalizeTokenVersion(User user) {
        if (user == null || user.getTokenVersion() == null || user.getTokenVersion() < 0) {
            return 0L;
        }
        return user.getTokenVersion();
    }
}
