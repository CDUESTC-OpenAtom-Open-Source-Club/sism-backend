package com.sism.iam.application.service;

import com.sism.iam.application.EmailService;
import com.sism.iam.domain.user.PasswordResetToken;
import com.sism.iam.domain.user.PasswordResetTokenRepository;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Value("${auth.password-reset.code-expiration-seconds:600}")
    private long codeExpirationSeconds;

    @Value("${auth.password-reset.resend-cooldown-seconds:60}")
    private long resendCooldownSeconds;

    @Value("${auth.password-reset.daily-limit:10}")
    private long dailyLimit;

    @Value("${auth.password-reset.ip-hourly-limit:20}")
    private long ipHourlyLimit;

    @Value("${auth.password-reset.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${auth.password-reset.lock-duration-seconds:1800}")
    private long lockDurationSeconds;

    @Transactional
    public SendResult sendResetCode(String email, String ipAddress, String userAgent) {
        String normalizedEmail = ContactInfoPolicy.normalizeEmail(email);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("该邮箱未注册"));

        LocalDateTime now = LocalDateTime.now();
        if (ipAddress != null && !ipAddress.isBlank()) {
            long hourlyIpCount = passwordResetTokenRepository.countByIpAddressCreatedAfter(
                    ipAddress,
                    now.minusHours(1)
            );
            if (hourlyIpCount >= ipHourlyLimit) {
                throw new IllegalStateException("请求过于频繁，请稍后再试");
            }
        }

        long dailyCount = passwordResetTokenRepository.countByEmailCreatedAfter(
                normalizedEmail,
                LocalDate.now().atStartOfDay()
        );
        if (dailyCount >= dailyLimit) {
            throw new IllegalStateException("今日发送次数已达上限，请明天再试");
        }

        var latest = passwordResetTokenRepository.findLatestByEmail(normalizedEmail).orElse(null);
        if (latest != null && latest.getCreatedAt() != null) {
            long elapsedSeconds = Duration.between(latest.getCreatedAt(), now).getSeconds();
            if (elapsedSeconds < resendCooldownSeconds) {
                long expiresIn = latest.getExpiresAt() == null ? codeExpirationSeconds
                        : Math.max(0, Duration.between(now, latest.getExpiresAt()).getSeconds());
                return new SendResult("验证码已发送到您的邮箱", expiresIn);
            }
        }

        String code = generateCode(normalizedEmail);
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setEmail(normalizedEmail);
        token.setToken(code);
        token.setUsed(false);
        token.setCreatedAt(now);
        token.setExpiresAt(now.plusSeconds(codeExpirationSeconds));
        token.setIpAddress(ipAddress);
        token.setUserAgent(userAgent);
        token.setFailedAttempts(0);
        token.setLockedUntil(null);
        passwordResetTokenRepository.save(token);

        emailService.sendPasswordResetCode(normalizedEmail, code);
        return new SendResult("验证码已发送到您的邮箱", codeExpirationSeconds);
    }

    @Transactional
    public void verifyCode(String email, String code) {
        PasswordResetToken token = getUsableToken(ContactInfoPolicy.normalizeEmail(email));
        if (!token.getToken().equals(code)) {
            registerFailure(token);
            throw new IllegalArgumentException("验证码错误或已过期");
        }
    }

    @Transactional
    public void resetPassword(String email, String code, String newPassword, String ipAddress, String userAgent) {
        PasswordPolicy.validateLength(newPassword);

        String normalizedEmail = ContactInfoPolicy.normalizeEmail(email);
        PasswordResetToken token = getUsableToken(normalizedEmail);
        if (!token.getToken().equals(code)) {
            registerFailure(token);
            throw new IllegalArgumentException("验证码错误或已过期");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() == null ? 1L : user.getTokenVersion() + 1L);
        user.setUpdatedAt(LocalDateTime.now());
        user = userRepository.save(user);

        token.setUsed(true);
        token.setFailedAttempts(0);
        token.setLockedUntil(null);
        passwordResetTokenRepository.save(token);
        passwordResetTokenRepository.markAllTokensUsedForUser(user.getId());

        String details = String.format("时间：%s%nIP：%s%n设备：%s",
                LocalDateTime.now(),
                ipAddress == null ? "unknown" : ipAddress,
                userAgent == null ? "unknown" : userAgent);
        emailService.sendPasswordResetSuccessNotification(normalizedEmail, details);
    }

    private PasswordResetToken getUsableToken(String normalizedEmail) {
        LocalDateTime now = LocalDateTime.now();
        PasswordResetToken token = passwordResetTokenRepository.findLatestActiveByEmail(normalizedEmail, now)
                .orElseThrow(() -> new IllegalArgumentException("验证码错误或已过期"));

        if (token.getLockedUntil() != null && token.getLockedUntil().isAfter(now)) {
            throw new IllegalStateException("验证码已被锁定，请30分钟后重试");
        }
        return token;
    }

    private void registerFailure(PasswordResetToken token) {
        int failures = token.getFailedAttempts() == null ? 0 : token.getFailedAttempts();
        failures++;
        token.setFailedAttempts(failures);
        if (failures >= maxFailedAttempts) {
            token.setLockedUntil(LocalDateTime.now().plusSeconds(lockDurationSeconds));
        }
        passwordResetTokenRepository.save(token);
    }

    private String generateCode(String email) {
        for (int i = 0; i < 5; i++) {
            String code = String.format("%06d", RANDOM.nextInt(1_000_000));
            if (!passwordResetTokenRepository.existsByTokenAndEmail(code, email)) {
                return code;
            }
        }
        throw new IllegalStateException("验证码生成失败，请稍后重试");
    }

    @Data
    @AllArgsConstructor
    public static class SendResult {
        private String message;
        private long expiresIn;
    }
}
