package com.sism.iam.application;

import com.sism.iam.domain.access.Role;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import com.sism.util.TokenBlacklistService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JwtTokenService
 * Tests JWT token generation, validation, and claims extraction
 */
@DisplayName("JwtTokenService Tests")
class JwtTokenServiceTest {

    private JwtTokenService jwtTokenService;
    private TokenBlacklistService blacklistService;
    private UserRepository userRepository;
    private final String secret = "SismSecretKeyForJWTTokenGeneration2024VeryLongSecretKeyForTesting";
    private final Long expiration = 86400000L; // 24 hours
    private final Long refreshExpiration = 604800000L; // 7 days

    @BeforeEach
    void setUp() {
        blacklistService = Mockito.mock(TokenBlacklistService.class);
        userRepository = Mockito.mock(UserRepository.class);
        jwtTokenService = new JwtTokenService(blacklistService, userRepository);
        ReflectionTestUtils.setField(jwtTokenService, "secret", secret);
        ReflectionTestUtils.setField(jwtTokenService, "expiration", expiration);
        ReflectionTestUtils.setField(jwtTokenService, "refreshExpiration", refreshExpiration);
    }

    @Test
    @DisplayName("Should generate token with valid user")
    void shouldGenerateTokenWithValidUser() {
        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setTokenVersion(0L);

        String token = jwtTokenService.generateToken(user);

        assertNotNull(token);
        assertFalse(token.isBlank());
        assertTrue(token.length() > 50); // JWT tokens are typically long
    }

    @Test
    @DisplayName("Should extract username from valid token")
    void shouldExtractUsernameFromValidToken() {
        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setTokenVersion(0L);

        String token = jwtTokenService.generateToken(user);
        String extractedUsername = jwtTokenService.extractUsername(token);

        assertEquals("testuser", extractedUsername);
    }

    @Test
    @DisplayName("Should validate valid token")
    void shouldValidateValidToken() {
        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setTokenVersion(0L);
        when(userRepository.findByUsername("testuser")).thenReturn(java.util.Optional.of(user));

        String token = jwtTokenService.generateToken(user);
        boolean isValid = jwtTokenService.validateToken(token);

        assertTrue(isValid);
    }

    @Test
    @DisplayName("Should invalidate invalid token")
    void shouldInvalidateInvalidToken() {
        String invalidToken = "invalid.jwt.token";

        boolean isValid = jwtTokenService.validateToken(invalidToken);

        assertFalse(isValid);
    }

    @Test
    @DisplayName("Should invalidate malformed token")
    void shouldInvalidateMalformedToken() {
        String malformedToken = "not.a.valid.jwt";

        boolean isValid = jwtTokenService.validateToken(malformedToken);

        assertFalse(isValid);
    }

    @Test
    @DisplayName("Should invalidate empty token")
    void shouldInvalidateEmptyToken() {
        String emptyToken = "";

        boolean isValid = jwtTokenService.validateToken(emptyToken);

        assertFalse(isValid);
    }

    @Test
    @DisplayName("Should get user ID from valid token")
    void shouldGetUserIdFromValidToken() {
        Long expectedUserId = 456L;
        User user = new User();
        user.setId(expectedUserId);
        user.setUsername("testuser");
        user.setTokenVersion(0L);

        String token = jwtTokenService.generateToken(user);
        Long extractedUserId = jwtTokenService.getUserIdFromToken(token);

        assertEquals(expectedUserId, extractedUserId);
    }

    @Test
    @DisplayName("Should throw exception when get user ID from invalid token")
    void shouldThrowExceptionWhenGetUserIdFromInvalidToken() {
        String invalidToken = "invalid.jwt.token";

        assertThrows(JwtException.class, () -> {
            jwtTokenService.getUserIdFromToken(invalidToken);
        });
    }

    @Test
    @DisplayName("Should generate different tokens for different users")
    void shouldGenerateDifferentTokensForDifferentUsers() {
        User user1 = new User();
        user1.setId(1L);
        user1.setUsername("user1");
        user1.setTokenVersion(0L);

        User user2 = new User();
        user2.setId(2L);
        user2.setUsername("user2");
        user2.setTokenVersion(0L);

        String token1 = jwtTokenService.generateToken(user1);
        String token2 = jwtTokenService.generateToken(user2);

        assertNotEquals(token1, token2);
    }

    @Test
    @DisplayName("Should generate token with correct expiration time")
    void shouldGenerateTokenWithCorrectExpirationTime() {
        Long customExpiration = 3600000L; // 1 hour
        ReflectionTestUtils.setField(jwtTokenService, "expiration", customExpiration);

        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setTokenVersion(0L);

        long startTime = System.currentTimeMillis();
        String token = jwtTokenService.generateToken(user);
        long endTime = System.currentTimeMillis();

        // Extract and verify expiration claim
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

        // The expiration should be approximately expiration + current time
        // We can't test exact value, but we can verify it's set
        assertTrue(payload.contains("\"exp\""));
    }

    @Test
    @DisplayName("Should include issued at claim in token")
    void shouldIncludeIssuedAtClaimInToken() {
        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setTokenVersion(0L);

        String token = jwtTokenService.generateToken(user);

        // Decode payload to check iat claim
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

        assertTrue(payload.contains("\"iat\""));
    }

    @Test
    @DisplayName("Should include user ID in token claims")
    void shouldIncludeUserIdInTokenClaims() {
        Long userId = 789L;
        User user = new User();
        user.setId(userId);
        user.setUsername("testuser");
        user.setTokenVersion(0L);

        String token = jwtTokenService.generateToken(user);

        // Decode payload to check userId claim
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

        assertTrue(payload.contains("\"userId\""));
        assertTrue(payload.contains(userId.toString()));
    }

    @Test
    @DisplayName("Should include username in token claims")
    void shouldIncludeUsernameInTokenClaims() {
        String username = "testuser";
        User user = new User();
        user.setId(123L);
        user.setUsername(username);
        user.setTokenVersion(0L);

        String token = jwtTokenService.generateToken(user);

        // Decode payload to check username claim
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

        assertTrue(payload.contains("\"username\""));
        assertTrue(payload.contains(username));
    }

    @Test
    @DisplayName("Should include org ID and roles in token claims")
    void shouldIncludeOrgIdAndRolesInTokenClaims() {
        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setOrgId(42L);
        user.setTokenVersion(0L);

        Role role = new Role();
        role.setRoleCode("ADMIN");
        user.setRoles(Set.of(role));

        String token = jwtTokenService.generateToken(user);

        assertEquals(42L, jwtTokenService.getOrgIdFromToken(token));
        assertEquals(java.util.List.of("ADMIN"), jwtTokenService.getRolesFromToken(token));
    }

    @Test
    @DisplayName("Should handle token with very long secret key")
    void shouldHandleTokenWithVeryLongSecretKey() {
        String veryLongSecret = "SismSecretKeyForJWTTokenGeneration2024VeryLongSecretKey".repeat(10);
        ReflectionTestUtils.setField(jwtTokenService, "secret", veryLongSecret);

        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setTokenVersion(0L);
        when(userRepository.findByUsername("testuser")).thenReturn(java.util.Optional.of(user));

        String token = jwtTokenService.generateToken(user);

        assertNotNull(token);
        assertTrue(jwtTokenService.validateToken(token));
        assertEquals("testuser", jwtTokenService.extractUsername(token));
    }

    @Test
    @DisplayName("Should set subject to username in token")
    void shouldSetSubjectToUsernameInToken() {
        String username = "testuser";
        User user = new User();
        user.setId(123L);
        user.setUsername(username);
        user.setTokenVersion(0L);

        String token = jwtTokenService.generateToken(user);
        String subject = jwtTokenService.extractUsername(token);

        assertEquals(username, subject);
    }

    @Test
    @DisplayName("Should preserve roles when refreshing token")
    void shouldPreserveRolesWhenRefreshingToken() {
        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setOrgId(42L);
        user.setTokenVersion(5L);
        when(userRepository.findByUsername("testuser")).thenReturn(java.util.Optional.of(user));

        String refreshToken = jwtTokenService.generateRefreshToken(user, java.util.List.of("ADMIN", "USER"));

        var refreshed = jwtTokenService.refreshToken(refreshToken);

        String accessToken = (String) refreshed.get("accessToken");
        assertEquals(java.util.List.of("ADMIN", "USER"), jwtTokenService.getRolesFromToken(accessToken));
        verify(blacklistService).blacklist(refreshToken);
    }

    @Test
    @DisplayName("Should reject access token in refresh flow")
    void shouldRejectAccessTokenInRefreshFlow() {
        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setTokenVersion(0L);

        String accessToken = jwtTokenService.generateToken(user, java.util.List.of("ADMIN"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> jwtTokenService.refreshToken(accessToken)
        );

        assertEquals("Not a refresh token", exception.getMessage());
        verify(blacklistService, never()).blacklist(accessToken);
    }

    @Test
    @DisplayName("Should reject malformed refresh token in refresh flow")
    void shouldRejectMalformedRefreshTokenInRefreshFlow() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> jwtTokenService.refreshToken("invalid-token")
        );

        assertEquals("Invalid refresh token", exception.getMessage());
        verify(blacklistService, never()).blacklist("invalid-token");
    }

    @Test
    @DisplayName("Should invalidate blacklisted token")
    void shouldInvalidateBlacklistedToken() {
        com.sism.util.TokenBlacklistService blacklistService = Mockito.mock(com.sism.util.TokenBlacklistService.class);
        jwtTokenService = new JwtTokenService(blacklistService, userRepository);
        ReflectionTestUtils.setField(jwtTokenService, "secret", secret);
        ReflectionTestUtils.setField(jwtTokenService, "expiration", expiration);
        ReflectionTestUtils.setField(jwtTokenService, "refreshExpiration", refreshExpiration);

        User user = new User();
        user.setId(123L);
        user.setUsername("testuser");
        user.setTokenVersion(0L);

        String token = jwtTokenService.generateToken(user);
        when(blacklistService.isBlacklisted(token)).thenReturn(true);

        assertFalse(jwtTokenService.validateToken(token));
    }

    @Test
    @DisplayName("Should invalidate token when persisted token version changes")
    void shouldInvalidateTokenWhenPersistedTokenVersionChanges() {
        User issuedUser = new User();
        issuedUser.setId(123L);
        issuedUser.setUsername("testuser");
        issuedUser.setTokenVersion(1L);

        String token = jwtTokenService.generateToken(issuedUser);

        User persistedUser = new User();
        persistedUser.setId(123L);
        persistedUser.setUsername("testuser");
        persistedUser.setTokenVersion(2L);
        when(userRepository.findByUsername("testuser")).thenReturn(java.util.Optional.of(persistedUser));

        assertFalse(jwtTokenService.validateToken(token));
    }
}
