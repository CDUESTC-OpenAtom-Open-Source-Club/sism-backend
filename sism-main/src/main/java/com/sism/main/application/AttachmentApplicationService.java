package com.sism.main.application;

import com.sism.main.interfaces.dto.AttachmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentApplicationService {
    // P-24 修复（2026-10-31）：前端文案与 Spring multipart（application.yml max-file-size）均为 30MB，
    // 服务端 20MB 上限与之不一致，21-30MB 的合法文件会被这里误拒
    private static final long MAX_UPLOAD_SIZE_BYTES = 30L * 1024 * 1024;
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/gif",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/msword",
            "application/vnd.ms-excel",
            "text/plain"
    );

    private final JdbcTemplate jdbcTemplate;

    @Value("${file.upload.path:${user.home}/.sism/uploads}")
    private String uploadPath;

    @Transactional
    public AttachmentResponse upload(MultipartFile file, Long uploadedBy) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (uploadedBy == null || uploadedBy <= 0) {
            throw new IllegalArgumentException("uploadedBy 必填");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE_BYTES) {
            throw new IllegalArgumentException("上传文件大小不能超过30MB");
        }
        if (file.getContentType() == null || ALLOWED_CONTENT_TYPES.stream().noneMatch(file.getContentType()::equalsIgnoreCase)) {
            throw new IllegalArgumentException("不支持的文件类型");
        }

        Path uploadRoot = resolveUploadRoot();
        Files.createDirectories(uploadRoot);

        String originalName = file.getOriginalFilename() == null ? "unknown" : Paths.get(file.getOriginalFilename()).getFileName().toString();
        String extension = "";
        int extensionIndex = originalName.lastIndexOf('.');
        if (extensionIndex >= 0 && extensionIndex < originalName.length() - 1) {
            extension = originalName.substring(extensionIndex + 1);
        }

        String objectKey = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
        Path targetPath = uploadRoot.resolve(objectKey);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO public.attachment (
                    storage_driver, bucket, object_key, public_url, original_name,
                    content_type, file_ext, size_bytes, uploaded_by, uploaded_at, remark, is_deleted
                )
                VALUES ('FILE', NULL, ?, NULL, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, NULL, false)
                RETURNING id
                """,
                Long.class,
                objectKey,
                originalName,
                file.getContentType(),
                extension.isBlank() ? null : extension,
                file.getSize(),
                uploadedBy
        );

        return getMetadata(id);
    }

    public AttachmentResponse getMetadata(Long id) {
        List<AttachmentResponse> rows = jdbcTemplate.query(
                """
                SELECT id,
                       original_name,
                       size_bytes,
                       content_type,
                       COALESCE(NULLIF(public_url, ''), CONCAT('/api/v1/attachments/', id, '/download')) AS url,
                       uploaded_by,
                       uploaded_at
                FROM public.attachment
                WHERE id = ?
                  AND COALESCE(is_deleted, false) = false
                """,
                (rs, rowNum) -> AttachmentResponse.builder()
                        .id(rs.getLong("id"))
                        .fileName(rs.getString("original_name"))
                        .fileSize(rs.getLong("size_bytes"))
                        .fileType(rs.getString("content_type"))
                        .url(rs.getString("url"))
                        .uploadedBy(rs.getLong("uploaded_by"))
                        .uploadedAt(rs.getObject("uploaded_at", OffsetDateTime.class))
                        .build(),
                id
        );

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Attachment not found: " + id);
        }

        return rows.get(0);
    }

    public Resource loadAsResource(Long id) throws MalformedURLException {
        String objectKey = jdbcTemplate.queryForObject(
                """
                SELECT object_key
                FROM public.attachment
                WHERE id = ?
                  AND COALESCE(is_deleted, false) = false
                """,
                String.class,
                id
        );
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Attachment object key not found: " + id);
        }

        Path filePath = resolveFilePath(objectKey);
        return new UrlResource(filePath.toUri());
    }

    private Path resolveUploadRoot() {
        return Paths.get(uploadPath).toAbsolutePath().normalize();
    }

    private Path resolveFilePath(String objectKey) {
        List<Path> candidateRoots = new ArrayList<>();
        Path uploadRoot = resolveUploadRoot();
        candidateRoots.add(uploadRoot);

        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        candidateRoots.add(workingDirectory.resolve("uploads").normalize());
        candidateRoots.add(workingDirectory.resolve("sism-main").resolve("uploads").normalize());

        Path parentDirectory = workingDirectory.getParent();
        if (parentDirectory != null) {
            candidateRoots.add(parentDirectory.resolve("uploads").normalize());
        }

        List<Path> candidates = new ArrayList<>();
        for (Path root : candidateRoots) {
            Path candidate = root.resolve(objectKey).normalize();
            if (!candidate.startsWith(root)) {
                throw new SecurityException("Invalid attachment object key");
            }
            candidates.add(candidate);
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        throw new IllegalArgumentException("Attachment file not found: " + objectKey);
    }
}
