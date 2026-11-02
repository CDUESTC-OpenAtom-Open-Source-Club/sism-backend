package com.sism.main.application;

import com.sism.main.interfaces.dto.AttachmentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Attachment Application Service Tests")
class AttachmentApplicationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should persist attachment without temporary url update")
    void shouldPersistAttachmentWithoutTemporaryUrlUpdate() throws IOException {
        AttachmentApplicationService service = new AttachmentApplicationService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "uploadPath", System.getProperty("java.io.tmpdir") + "/sism-main-attachment-test");
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes());
        AttachmentResponse metadata = AttachmentResponse.builder()
                .id(7L)
                .fileName("report.pdf")
                .build();

        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(7L);
        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), eq(7L)))
                .thenReturn(java.util.List.of(metadata));

        AttachmentResponse response = service.upload(file, 12L);

        assertEquals(7L, response.getId());
        verify(jdbcTemplate, never()).update(any(String.class), any(), any());
    }

    @Test
    @DisplayName("Should reject attachment object key traversal")
    void shouldRejectAttachmentObjectKeyTraversal() throws Exception {
        AttachmentApplicationService service = new AttachmentApplicationService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "uploadPath", System.getProperty("java.io.tmpdir") + "/sism-main-attachment-test");

        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(9L)))
                .thenReturn("../../etc/passwd");

        assertThrows(SecurityException.class, () -> service.loadAsResource(9L));
    }

    @Test
    @DisplayName("Should reject missing attachment file instead of returning fallback path")
    void shouldRejectMissingAttachmentFile() {
        AttachmentApplicationService service = new AttachmentApplicationService(jdbcTemplate);
        Path uploadDir = Path.of(System.getProperty("java.io.tmpdir"), "sism-main-attachment-missing-test");
        ReflectionTestUtils.setField(service, "uploadPath", uploadDir.toString());
        assertDoesNotThrow(() -> Files.createDirectories(uploadDir));

        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(10L)))
                .thenReturn("missing.pdf");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.loadAsResource(10L));
        assertEquals("Attachment file not found: missing.pdf", exception.getMessage());
    }

    @Test
    @DisplayName("Should reject oversized upload")
    void shouldRejectOversizedUpload() {
        AttachmentApplicationService service = new AttachmentApplicationService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "uploadPath", System.getProperty("java.io.tmpdir") + "/sism-main-attachment-test");

        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes()) {
            @Override
            public long getSize() {
                return 31L * 1024 * 1024;
            }
        };

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.upload(file, 12L));
        assertEquals("上传文件大小不能超过30MB", exception.getMessage());
    }

    @Test
    @DisplayName("Should reject unsupported content type")
    void shouldRejectUnsupportedContentType() {
        AttachmentApplicationService service = new AttachmentApplicationService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "uploadPath", System.getProperty("java.io.tmpdir") + "/sism-main-attachment-test");
        MockMultipartFile file = new MockMultipartFile("file", "script.sh", "application/x-sh", "echo".getBytes());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.upload(file, 12L));
        assertEquals("不支持的文件类型", exception.getMessage());
    }
}
