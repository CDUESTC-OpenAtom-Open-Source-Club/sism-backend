package com.sism.iam.application.service;

import com.sism.iam.application.JwtTokenService;
import com.sism.iam.application.dto.LoginRequest;
import com.sism.iam.application.dto.LoginResponse;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.shared.domain.exception.AuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService
 * Tests authentication business logic including login and registration
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private OrganizationRepository organizationRepository;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                jwtTokenService,
                passwordEncoder,
                loginAttemptService,
                organizationRepository
        );
    }

    @Test
    @DisplayName("Should login successfully with valid credentials")
    void shouldLoginSuccessfully() {
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("password123");

        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setRealName("Test User");
        user.setPassword("encodedPassword");
        user.setIsActive(true);
        user.setOrgId(35L);
        SysOrg org = SysOrg.create("战略发展部", OrgType.admin);
        org.setId(35L);

        when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encodedPassword"))
                .thenReturn(true);
        when(organizationRepository.findById(35L)).thenReturn(Optional.of(org));
        when(jwtTokenService.generateToken(eq(user), anyList()))
                .thenReturn("jwt.token.here");

        LoginResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("jwt.token.here", response.getToken());
        assertEquals(1L, response.getUserId());
        assertEquals("testuser", response.getUsername());
        assertEquals("Test User", response.getRealName());
        verify(userRepository).findByUsername("testuser");
        verify(passwordEncoder).matches("password123", "encodedPassword");
        verify(jwtTokenService).generateToken(eq(user), anyList());
        verify(loginAttemptService).assertNotBlocked("testuser", "global");
        verify(loginAttemptService).recordSuccess("testuser", "global");
    }

    @Test
    @DisplayName("Should throw exception when login with blank username")
    void shouldThrowExceptionWhenLoginWithBlankUsername() {
        LoginRequest request = new LoginRequest();
        request.setAccount("");
        request.setPassword("password123");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(request)
        );

        assertEquals("请输入账号", exception.getMessage());
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("Should throw exception when login with null username")
    void shouldThrowExceptionWhenLoginWithNullUsername() {
        LoginRequest request = new LoginRequest();
        request.setAccount(null);
        request.setPassword("password123");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(request)
        );

        assertEquals("请输入账号", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when login with blank password")
    void shouldThrowExceptionWhenLoginWithBlankPassword() {
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(request)
        );

        assertEquals("请输入密码", exception.getMessage());
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("Should throw exception when login with non-existent user")
    void shouldThrowExceptionWhenLoginWithNonExistentUser() {
        LoginRequest request = new LoginRequest();
        request.setAccount("nonexistent");
        request.setPassword("password123");

        when(userRepository.findByUsername("nonexistent"))
                .thenReturn(Optional.empty());

        AuthenticationException exception = assertThrows(
                AuthenticationException.class,
                () -> authService.login(request)
        );

        assertEquals("用户名或密码错误", exception.getMessage());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("Should throw exception when login with wrong password")
    void shouldThrowExceptionWhenLoginWithWrongPassword() {
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("wrongpassword");

        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("encodedPassword");
        user.setIsActive(true);

        when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "encodedPassword"))
                .thenReturn(false);

        AuthenticationException exception = assertThrows(
                AuthenticationException.class,
                () -> authService.login(request)
        );

        assertEquals("用户名或密码错误", exception.getMessage());
        verify(jwtTokenService, never()).generateToken(any());
        verify(loginAttemptService).recordFailure("testuser", "global");
    }

    @Test
    @DisplayName("Should throw exception when login with inactive account")
    void shouldThrowExceptionWhenLoginWithInactiveAccount() {
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("password123");

        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("encodedPassword");
        user.setIsActive(false);

        when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encodedPassword"))
                .thenReturn(true);

        AuthenticationException exception = assertThrows(
                AuthenticationException.class,
                () -> authService.login(request)
        );

        assertEquals("账号已被禁用，请联系管理员", exception.getMessage());
        verify(jwtTokenService, never()).generateToken(any());
    }

    @Test
    @DisplayName("Should register new user successfully")
    void shouldRegisterNewUserSuccessfully() {
        String username = "newuser";
        String password = "password123";
        String realName = "New User";

        when(userRepository.findByUsername(username))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode(password))
                .thenReturn("encodedPassword");

        User savedUser = new User();
        savedUser.setId(1L);
        savedUser.setUsername(username);
        savedUser.setRealName(realName);
        savedUser.setPassword("encodedPassword");
        savedUser.setIsActive(true);

        when(userRepository.save(any(User.class)))
                .thenReturn(savedUser);

        User result = authService.register(username, password, realName);

        assertNotNull(result);
        assertEquals(username, result.getUsername());
        assertEquals(realName, result.getRealName());
        assertEquals("encodedPassword", result.getPassword());
        assertTrue(result.getIsActive());
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw exception when register with blank username")
    void shouldThrowExceptionWhenRegisterWithBlankUsername() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register("", "password123", "Test User")
        );

        assertEquals("请输入用户名", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw exception when register with blank password")
    void shouldThrowExceptionWhenRegisterWithBlankPassword() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register("testuser", "", "Test User")
        );

        assertEquals("请输入密码", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw exception when register with existing username")
    void shouldThrowExceptionWhenRegisterWithExistingUsername() {
        String username = "existinguser";

        User existingUser = new User();
        existingUser.setUsername(username);

        when(userRepository.findByUsername(username))
                .thenReturn(Optional.of(existingUser));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(username, "password123", "Test User")
        );

        assertEquals("用户名已存在", exception.getMessage());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw exception when register with invalid username characters")
    void shouldThrowExceptionWhenRegisterWithInvalidUsernameCharacters() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register("test-user", "password123", "Test User")
        );

        assertEquals("用户名只能包含字母、数字和下划线", exception.getMessage());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should validate token successfully")
    void shouldValidateTokenSuccessfully() {
        String token = "valid.jwt.token";

        when(jwtTokenService.validateToken(token))
                .thenReturn(true);

        boolean result = authService.validateToken(token);

        assertTrue(result);
        verify(jwtTokenService).validateToken(token);
    }

    @Test
    @DisplayName("Should invalidate invalid token")
    void shouldInvalidateInvalidToken() {
        String token = "invalid.jwt.token";

        when(jwtTokenService.validateToken(token))
                .thenReturn(false);

        boolean result = authService.validateToken(token);

        assertFalse(result);
    }

    @Test
    @DisplayName("Should blacklist token on logout")
    void shouldBlacklistTokenOnLogout() {
        String token = "jwt.token";

        authService.logout(token);

        verify(jwtTokenService).blacklistToken(token);
    }

    @Test
    @DisplayName("Should get user ID from token")
    void shouldGetUserIdFromToken() {
        String token = "valid.jwt.token";
        Long userId = 123L;

        when(jwtTokenService.getUserIdFromToken(token))
                .thenReturn(userId);

        Long result = authService.getUserIdFromToken(token);

        assertEquals(userId, result);
        verify(jwtTokenService).getUserIdFromToken(token);
    }
}
