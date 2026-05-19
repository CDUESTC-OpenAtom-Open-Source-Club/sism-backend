package com.sism.iam.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.iam.application.service.PasswordResetService;
import com.sism.iam.application.service.AuthService;
import com.sism.iam.application.service.UserService;
import com.sism.iam.domain.user.User;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import com.sism.organization.domain.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController admin org access tests")
class AuthControllerAdminAccessTest {

    @Mock
    private AuthService authService;

    @Mock
    private UserService userService;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private CurrentUser currentUser;

    @Test
    @DisplayName("getAllUsers should allow admin org users")
    void getAllUsersShouldAllowAdminOrgUsers() {
        AuthController controller = controller();
        SysOrg adminOrg = SysOrg.create("新管理员组织", OrgType.admin);
        adminOrg.setId(999L);

        when(currentUser.getOrgId()).thenReturn(999L);
        when(organizationRepository.findById(999L)).thenReturn(Optional.of(adminOrg));
        when(userService.findPage(0, 20)).thenReturn(Page.empty());

        ResponseEntity<ApiResponse<AuthController.UserListPageResponse>> response =
                controller.getAllUsers(currentUser, 0, 20);

        assertEquals(200, response.getStatusCodeValue());
        verify(userService).findPage(0, 20);
    }

    @Test
    @DisplayName("getAllUsers should include organization name in list response")
    void getAllUsersShouldIncludeOrganizationNameInListResponse() {
        AuthController controller = controller();
        SysOrg adminOrg = SysOrg.create("管理员组织", OrgType.admin);
        adminOrg.setId(501L);
        User user = mock(User.class);

        when(currentUser.getOrgId()).thenReturn(501L);
        when(organizationRepository.findById(501L)).thenReturn(Optional.of(adminOrg));
        when(userService.findPage(0, 20)).thenReturn(new PageImpl<>(List.of(user)));
        when(user.getId()).thenReturn(124L);
        when(user.getUsername()).thenReturn("admin");
        when(user.getRealName()).thenReturn("系统管理员");
        when(user.getOrgId()).thenReturn(501L);
        when(user.getIsActive()).thenReturn(true);
        when(user.getRoles()).thenReturn(Set.of());

        ResponseEntity<ApiResponse<AuthController.UserListPageResponse>> response =
                controller.getAllUsers(currentUser, 0, 20);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("管理员组织", response.getBody().getData().getContent().get(0).getOrgName());
    }

    @Test
    @DisplayName("getAllUsers should deny non-admin org users")
    void getAllUsersShouldDenyNonAdminOrgUsers() {
        AuthController controller = controller();
        SysOrg nonAdminOrg = SysOrg.create("教务处", OrgType.functional);
        nonAdminOrg.setId(44L);

        when(currentUser.getOrgId()).thenReturn(44L);
        when(organizationRepository.findById(44L)).thenReturn(Optional.of(nonAdminOrg));

        ResponseEntity<ApiResponse<AuthController.UserListPageResponse>> response =
                controller.getAllUsers(currentUser, 0, 20);

        assertEquals(403, response.getStatusCodeValue());
        verify(userService, never()).findPage(0, 20);
    }

    @Test
    @DisplayName("getUserById should deny access when current user org is missing")
    void getUserByIdShouldDenyAccessWhenCurrentUserOrgIsMissing() {
        AuthController controller = controller();

        when(currentUser.getOrgId()).thenReturn(404L);
        when(organizationRepository.findById(404L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<AuthController.UserSummaryResponse>> response =
                controller.getUserById(currentUser, 124L);

        assertEquals(403, response.getStatusCodeValue());
        verify(userService, never()).findById(124L);
    }

    @Test
    @DisplayName("getUserById should allow admin org access regardless of org id value")
    void getUserByIdShouldAllowAdminOrgAccessRegardlessOfOrgIdValue() {
        AuthController controller = controller();
        SysOrg adminOrg = SysOrg.create("管理员组织", OrgType.admin);
        adminOrg.setId(501L);
        User user = mock(User.class);

        when(currentUser.getOrgId()).thenReturn(501L);
        when(organizationRepository.findById(501L)).thenReturn(Optional.of(adminOrg));
        when(userService.findById(124L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(124L);
        when(user.getUsername()).thenReturn("admin");
        when(user.getRealName()).thenReturn("系统管理员");
        when(user.getOrgId()).thenReturn(501L);
        when(user.getIsActive()).thenReturn(true);
        when(user.getRoles()).thenReturn(Set.of());

        ResponseEntity<ApiResponse<AuthController.UserSummaryResponse>> response =
                controller.getUserById(currentUser, 124L);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(124L, response.getBody().getData().getId());
    }

    @Test
    @DisplayName("getUsersByOrgId should allow authenticated non-admin users")
    void getUsersByOrgIdShouldAllowAuthenticatedNonAdminUsers() {
        AuthController controller = controller();
        User user = mock(User.class);

        when(userService.findByOrgId(53L)).thenReturn(List.of(user));
        when(user.getId()).thenReturn(124L);
        when(user.getUsername()).thenReturn("demo");
        when(user.getRealName()).thenReturn("演示用户");
        when(user.getOrgId()).thenReturn(53L);
        when(user.getIsActive()).thenReturn(true);
        when(user.getRoles()).thenReturn(Set.of());

        ResponseEntity<ApiResponse<List<AuthController.UserSummaryResponse>>> response =
                controller.getUsersByOrgId(currentUser, 53L);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(1, response.getBody().getData().size());
        verify(userService).findByOrgId(53L);
    }

    private AuthController controller() {
        return new AuthController(authService, userService, organizationRepository, passwordResetService);
    }
}
