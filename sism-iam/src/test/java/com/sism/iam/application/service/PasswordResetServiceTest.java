package com.sism.iam.application.service;

import com.sism.iam.application.EmailService;
import com.sism.iam.domain.user.PasswordResetToken;
import com.sism.iam.domain.user.PasswordResetTokenRepository;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService Tests")
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(passwordResetService, "codeExpirationSeconds", 600L);
        ReflectionTestUtils.setField(passwordResetService, "resendCooldownSeconds", 60L);
        ReflectionTestUtils.setField(passwordResetService, "dailyLimit", 10L);
        ReflectionTestUtils.setField(passwordResetService, "ipHourlyLimit", 20L);
        ReflectionTestUtils.setField(passwordResetService, "maxFailedAttempts", 5);
        ReflectionTestUtils.setField(passwordResetService, "lockDurationSeconds", 1800L);
    }

    @Test
    @DisplayName("sendResetCode should send verification code to registered email")
    void sendResetCodeShouldSendVerificationCode() {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@example.com");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.countByEmailCreatedAfter(any(), any())).thenReturn(0L);
        when(passwordResetTokenRepository.countByIpAddressCreatedAfter(any(), any())).thenReturn(0L);

        PasswordResetService.SendResult result = passwordResetService.sendResetCode(
                "USER@example.com",
                "127.0.0.1",
                "JUnit"
        );

        assertEquals("验证码已发送到您的邮箱", result.getMessage());
        assertEquals(600L, result.getExpiresIn());
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetCode(any(), any());
    }

    @Test
    @DisplayName("sendResetCode should return existing status during cooldown")
    void sendResetCodeShouldReuseCooldownWindow() {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@example.com");
        PasswordResetToken token = new PasswordResetToken();
        token.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        token.setExpiresAt(LocalDateTime.now().plusSeconds(500));

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.countByEmailCreatedAfter(any(), any())).thenReturn(0L);
        when(passwordResetTokenRepository.countByIpAddressCreatedAfter(any(), any())).thenReturn(0L);
        when(passwordResetTokenRepository.findLatestByEmail("user@example.com")).thenReturn(Optional.of(token));

        PasswordResetService.SendResult result = passwordResetService.sendResetCode(
                "user@example.com",
                "127.0.0.1",
                "JUnit"
        );

        assertEquals("验证码已发送到您的邮箱", result.getMessage());
        verify(passwordResetTokenRepository, never()).save(any(PasswordResetToken.class));
        verify(emailService, never()).sendPasswordResetCode(any(), any());
    }

    @Test
    @DisplayName("verifyCode should reject mismatched code")
    void verifyCodeShouldRejectMismatchedCode() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("123456");
        token.setFailedAttempts(0);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));

        when(passwordResetTokenRepository.findLatestActiveByEmail(any(), any()))
                .thenReturn(Optional.of(token));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> passwordResetService.verifyCode("user@example.com", "000000")
        );

        assertEquals("验证码错误或已过期", exception.getMessage());
        verify(passwordResetTokenRepository).save(token);
    }

    @Test
    @DisplayName("resetPassword should update password and invalidate outstanding tokens")
    void resetPasswordShouldUpdatePasswordAndInvalidateOutstandingTokens() {
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(7L);
        token.setToken("123456");
        token.setFailedAttempts(0);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));

        User user = new User();
        user.setId(7L);
        user.setTokenVersion(2L);

        when(passwordResetTokenRepository.findLatestActiveByEmail(any(), any()))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(passwordEncoder.encode("new-pass-123")).thenReturn("encoded");

        passwordResetService.resetPassword("user@example.com", "123456", "new-pass-123", "127.0.0.1", "JUnit");

        assertEquals("encoded", user.getPassword());
        assertEquals(3L, user.getTokenVersion());
        verify(userRepository).save(user);
        verify(passwordResetTokenRepository).markAllTokensUsedForUser(7L);
        verify(emailService).sendPasswordResetSuccessNotification(any(), any());
    }

    @Test
    @DisplayName("resetPassword should lock token after too many failures")
    void resetPasswordShouldLockTokenAfterTooManyFailures() {
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(7L);
        token.setToken("123456");
        token.setFailedAttempts(4);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));

        when(passwordResetTokenRepository.findLatestActiveByEmail(any(), any()))
                .thenReturn(Optional.of(token));

        assertThrows(
                IllegalArgumentException.class,
                () -> passwordResetService.resetPassword("user@example.com", "000000", "new-pass-123", "127.0.0.1", "JUnit")
        );

        assertEquals(5, token.getFailedAttempts());
        verify(passwordResetTokenRepository).save(token);
    }
}
