package com.sism.iam.application.service;

import com.sism.iam.domain.user.User;
import com.sism.iam.domain.access.Role;
import com.sism.iam.domain.user.UserRepository;
import com.sism.iam.domain.access.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserService
 * Tests user management business logic
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Tests")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, roleRepository, passwordEncoder);
        lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepository.findByPhone(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Should create user with required parameters")
    void shouldCreateUserWithRequiredParameters() {
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("john_doe");
        mockUser.setRealName("John Doe");
        mockUser.setOrgId(10L);

        when(userRepository.save(any(User.class)))
                .thenReturn(mockUser);
        when(passwordEncoder.encode("password")).thenReturn("encodedPassword");

        User created = userService.createUser("john_doe", "password", "John Doe", "john@example.com", "13800138000", 10L, List.of());

        assertNotNull(created);
        assertEquals("john_doe", created.getUsername());
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should find user by ID")
    void shouldFindUserById() {
        Long userId = 1L;
        User user = new User();
        user.setId(userId);
        user.setUsername("john_doe");

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        Optional<User> found = userService.findById(userId);

        assertTrue(found.isPresent());
        assertEquals("john_doe", found.get().getUsername());
    }

    @Test
    @DisplayName("Should find user by username")
    void shouldFindUserByUsername() {
        String username = "john_doe";
        User user = new User();
        user.setUsername(username);
        user.setRealName("John Doe");

        when(userRepository.findByUsername(username))
                .thenReturn(Optional.of(user));

        Optional<User> found = userService.findByUsername(username);

        assertTrue(found.isPresent());
        assertEquals(username, found.get().getUsername());
    }

    @Test
    @DisplayName("Should find users by page without loading all data")
    void shouldFindUsersByPage() {
        User user = new User();
        user.setId(1L);
        user.setUsername("paged_user");

        when(userRepository.findAll(PageRequest.of(0, 20)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));

        Page<User> page = userService.findPage(0, 20);

        assertEquals(1, page.getTotalElements());
        assertEquals("paged_user", page.getContent().get(0).getUsername());
    }

    @Test
    @DisplayName("Should lock user")
    void shouldLockUser() {
        Long userId = 1L;
        User user = new User();
        user.setId(userId);
        user.setIsActive(true);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));
        when(userRepository.save(user))
                .thenReturn(user);

        userService.lockUser(userId);

        assertFalse(user.getIsActive());
    }

    @Test
    @DisplayName("Should unlock user")
    void shouldUnlockUser() {
        Long userId = 1L;
        User user = new User();
        user.setId(userId);
        user.setIsActive(false);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));
        when(userRepository.save(user))
                .thenReturn(user);

        userService.unlockUser(userId);

        assertTrue(user.getIsActive());
    }

    @Test
    @DisplayName("Should throw exception when lock non-existent user")
    void shouldThrowExceptionWhenLockNonExistentUser() {
        Long userId = 999L;

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            userService.lockUser(userId);
        });

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw exception when unlock non-existent user")
    void shouldThrowExceptionWhenUnlockNonExistentUser() {
        Long userId = 999L;

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            userService.unlockUser(userId);
        });

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should return empty when find by non-existent ID")
    void shouldReturnEmptyWhenFindByNonExistentId() {
        Long userId = 999L;

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        Optional<User> found = userService.findById(userId);

        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("Should return empty when find by non-existent username")
    void shouldReturnEmptyWhenFindByNonExistentUsername() {
        String username = "nonexistent";

        when(userRepository.findByUsername(username))
                .thenReturn(Optional.empty());

        Optional<User> found = userService.findByUsername(username);

        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("Should create user with all parameters including org")
    void shouldCreateUserWithAllParameters() {
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("john_doe");
        mockUser.setRealName("John Doe");
        mockUser.setOrgId(10L);

        when(userRepository.save(any(User.class)))
                .thenReturn(mockUser);
        when(passwordEncoder.encode("password")).thenReturn("encodedPassword");

        User created = userService.createUser(
                "john_doe",
                "password",
                "John Doe",
                "john@example.com",
                "13800138000",
                10L,
                List.of()
        );

        assertNotNull(created);
        assertEquals("john_doe", created.getUsername());
        assertEquals(10L, created.getOrgId());
    }

    @Test
    @DisplayName("Should create user with minimal required parameters")
    void shouldCreateUserWithMinimalParameters() {
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("jane_doe");
        mockUser.setRealName("Jane Doe");
        mockUser.setOrgId(20L);

        when(userRepository.save(any(User.class)))
                .thenReturn(mockUser);
        when(passwordEncoder.encode("password")).thenReturn("encodedPassword");

        User created = userService.createUser(
                "jane_doe",
                "password",
                "Jane Doe",
                null,
                null,
                20L,
                List.of()
        );

        assertNotNull(created);
        assertEquals("jane_doe", created.getUsername());
    }

    @Test
    @DisplayName("Should create user with roles")
    void shouldCreateUserWithRoles() {
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("admin_user");
        mockUser.setRealName("Admin User");

        Role adminRole = new Role();
        adminRole.setId(1L);
        adminRole.setRoleCode("ADMIN");

        when(roleRepository.findByRoleCode("ADMIN"))
                .thenReturn(Optional.of(adminRole));
        when(userRepository.save(any(User.class)))
                .thenReturn(mockUser);
        when(passwordEncoder.encode("password")).thenReturn("encodedPassword");

        User created = userService.createUser(
                "admin_user",
                "password",
                "Admin User",
                "admin@example.com",
                "13800138000",
                10L,
                List.of("ADMIN")
        );

        assertNotNull(created);
        assertEquals("admin_user", created.getUsername());
    }

    @Test
    @DisplayName("Should reject user creation when username is too short")
    void shouldRejectUserCreationWhenUsernameIsTooShort() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> userService.createUser("ab", "password", "John Doe", null, null, 10L, List.of())
        );

        assertEquals("用户名长度需为3-20个字符", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should reject user creation when username contains invalid characters")
    void shouldRejectUserCreationWhenUsernameContainsInvalidCharacters() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> userService.createUser("john-doe", "password", "John Doe", null, null, 10L, List.of())
        );

        assertEquals("用户名只能包含字母、数字和下划线", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

}
