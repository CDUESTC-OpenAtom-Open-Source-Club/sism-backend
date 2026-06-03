package com.sism.iam.application.service;

import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService Tests")
class UserProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserProfileService userProfileService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(userRepository.findByEmail(any())).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.lenient().when(userRepository.findByPhone(any())).thenReturn(java.util.Optional.empty());
    }

    @Test
    @DisplayName("changePassword should increment token version")
    void changePasswordShouldIncrementTokenVersion() {
        User user = new User();
        user.setPassword("encoded-old");
        user.setTokenVersion(3L);

        when(passwordEncoder.matches("old-pass", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-pass-123")).thenReturn("encoded-new");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userProfileService.changePassword(user, "old-pass", "new-pass-123", "new-pass-123");

        assertEquals("encoded-new", updated.getPassword());
        assertEquals(4L, updated.getTokenVersion());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("changePassword should initialize token version when missing")
    void changePasswordShouldInitializeTokenVersionWhenMissing() {
        User user = new User();
        user.setPassword("encoded-old");
        user.setTokenVersion(null);

        when(passwordEncoder.matches("old-pass", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-pass-123")).thenReturn("encoded-new");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userProfileService.changePassword(user, "old-pass", "new-pass-123", "new-pass-123");

        assertEquals(1L, updated.getTokenVersion());
    }

    @Test
    @DisplayName("changePassword should reject wrong current password")
    void changePasswordShouldRejectWrongCurrentPassword() {
        User user = new User();
        user.setPassword("encoded-old");

        when(passwordEncoder.matches("wrong-pass", "encoded-old")).thenReturn(false);

        assertThrows(
                IllegalArgumentException.class,
                () -> userProfileService.changePassword(user, "wrong-pass", "new-pass-123", "new-pass-123")
        );
    }

    @Test
    @DisplayName("changePassword should reject password without letters")
    void changePasswordShouldRejectPasswordWithoutLetters() {
        User user = new User();
        user.setPassword("encoded-old");

        when(passwordEncoder.matches("old-pass", "encoded-old")).thenReturn(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> userProfileService.changePassword(user, "old-pass", "12345678", "12345678")
        );
    }

    @Test
    @DisplayName("updateContact should normalize email and phone")
    void updateContactShouldNormalizeEmailAndPhone() {
        User user = new User();
        user.setId(11L);

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userProfileService.updateContact(user, "USER@Example.com ", "13800138000");

        assertEquals("user@example.com", updated.getEmail());
        assertEquals("13800138000", updated.getPhone());
    }

    @Test
    @DisplayName("updateContact should clear values when null")
    void updateContactShouldClearValuesWhenNull() {
        User user = new User();
        user.setId(11L);
        user.setEmail("a@example.com");
        user.setPhone("13800138000");

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userProfileService.updateContact(user, null, null);

        assertNull(updated.getEmail());
        assertNull(updated.getPhone());
    }

    @Test
    @DisplayName("findCurrentUserById should return null when user id is missing")
    void findCurrentUserByIdShouldReturnNullWhenUserIdIsMissing() {
        assertNull(userProfileService.findCurrentUserById(null));
        verify(userRepository, never()).findById(any());
    }
}
