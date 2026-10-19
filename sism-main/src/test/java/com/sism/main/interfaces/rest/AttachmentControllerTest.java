package com.sism.main.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.main.application.AttachmentApplicationService;
import com.sism.main.interfaces.dto.AttachmentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Attachment Controller Tests")
class AttachmentControllerTest {

    @Mock
    private AttachmentApplicationService attachmentApplicationService;

    @InjectMocks
    private AttachmentController attachmentController;

    @Test
    @DisplayName("Should reject upload when uploadedBy does not match current user")
    void shouldRejectUploadWhenUploadedByDoesNotMatchCurrentUser() throws Exception {
        CurrentUser currentUser = regularUser(12L);
        Authentication authentication = authentication(currentUser);
        MultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ApiResponse<AttachmentResponse>> response = attachmentController.upload(file, 99L, authentication);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(attachmentApplicationService, never()).upload(any(MultipartFile.class), anyLong());
    }

    @Test
    @DisplayName("Should allow owner to download attachment")
    void shouldAllowOwnerToDownloadAttachment() throws Exception {
        CurrentUser currentUser = regularUser(12L);
        Authentication authentication = authentication(currentUser);
        AttachmentResponse metadata = AttachmentResponse.builder()
                .id(7L)
                .fileName("report.pdf")
                .fileSize(10L)
                .fileType("application/pdf")
                .url("/api/v1/attachments/7/download")
                .uploadedBy(12L)
                .uploadedAt(OffsetDateTime.parse("2026-04-07T00:00:00Z"))
                .build();

        when(attachmentApplicationService.getMetadata(7L)).thenReturn(metadata);
        when(attachmentApplicationService.loadAsResource(7L)).thenReturn(new ByteArrayResource("content".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<?> response = attachmentController.download(7L, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertInstanceOf(org.springframework.core.io.Resource.class, response.getBody());
        verify(attachmentApplicationService).getMetadata(7L);
        verify(attachmentApplicationService).loadAsResource(7L);
    }

    @Test
    @DisplayName("Should allow metadata access for any authenticated user (no fine-grained permission)")
    void shouldAllowMetadataAccessForAnyAuthenticatedUser() throws Exception {
        // 2026-10-19 用户定案：附件不设细粒度下载权限——部门之间流转材料，
        // 细分权限会阻塞正常查阅（填报人看不到本部门上报资料）。任何登录用户均可访问。
        CurrentUser currentUser = regularUser(12L);
        Authentication authentication = authentication(currentUser);
        AttachmentResponse metadata = AttachmentResponse.builder()
                .id(7L)
                .fileName("report.pdf")
                .fileSize(10L)
                .fileType("application/pdf")
                .url("/api/v1/attachments/7/download")
                .uploadedBy(88L)
                .uploadedAt(OffsetDateTime.parse("2026-04-07T00:00:00Z"))
                .build();

        when(attachmentApplicationService.getMetadata(7L)).thenReturn(metadata);

        ResponseEntity<ApiResponse<AttachmentResponse>> response = attachmentController.metadata(7L, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(attachmentApplicationService).getMetadata(7L);
    }

    @Test
    @DisplayName("Should allow admin to upload on behalf of another user")
    void shouldAllowAdminToUploadOnBehalfOfAnotherUser() throws Exception {
        CurrentUser admin = adminUser(1L);
        Authentication authentication = authentication(admin);
        MultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes(StandardCharsets.UTF_8));
        AttachmentResponse attachmentResponse = AttachmentResponse.builder()
                .id(7L)
                .fileName("report.pdf")
                .fileSize(4L)
                .fileType("application/pdf")
                .url("/api/v1/attachments/7/download")
                .uploadedBy(99L)
                .uploadedAt(OffsetDateTime.parse("2026-04-07T00:00:00Z"))
                .build();
        when(attachmentApplicationService.upload(file, 99L)).thenReturn(attachmentResponse);

        ResponseEntity<ApiResponse<AttachmentResponse>> response = attachmentController.upload(file, 99L, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof ApiResponse<?>);
        verify(attachmentApplicationService).upload(file, 99L);
    }

    @Test
    @DisplayName("Should reject unsupported principal implementations")
    void shouldRejectUnsupportedPrincipalImplementations() throws Exception {
        Authentication authentication = new TestingAuthenticationToken("plain-user", null);
        MultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ApiResponse<AttachmentResponse>> response = attachmentController.upload(file, 12L, authentication);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(attachmentApplicationService, never()).upload(any(MultipartFile.class), anyLong());
    }

    private CurrentUser regularUser(Long id) {
        return new CurrentUser(
                id,
                "user-" + id,
                "User " + id,
                null,
                100L,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    private CurrentUser adminUser(Long id) {
        return new CurrentUser(
                id,
                "admin-" + id,
                "Admin " + id,
                null,
                100L,
                List.of(new SimpleGrantedAuthority("ROLE_VICE_PRESIDENT"))
        );
    }

    private Authentication authentication(CurrentUser currentUser) {
        return new TestingAuthenticationToken(currentUser, null, currentUser.getAuthorities());
    }
}
