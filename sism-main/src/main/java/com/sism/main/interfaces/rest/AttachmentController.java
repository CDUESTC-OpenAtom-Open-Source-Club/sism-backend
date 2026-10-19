package com.sism.main.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.main.application.AttachmentApplicationService;
import com.sism.main.interfaces.dto.AttachmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/attachments")
@RequiredArgsConstructor
@Tag(name = "Attachments", description = "附件上传与下载接口")
public class AttachmentController {

    private final AttachmentApplicationService attachmentApplicationService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传附件")
    public ResponseEntity<ApiResponse<AttachmentResponse>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("uploadedBy") Long uploadedBy,
            Authentication authentication
    ) throws IOException {
        UserIdentity currentUser = resolveCurrentUser(authentication);
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }
        if (!canActAsUploader(currentUser, uploadedBy)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "当前用户无权以该身份上传附件"));
        }
        // Upload first persists the file into attachment storage, then returns metadata for later association.
        return ResponseEntity.ok(ApiResponse.success(attachmentApplicationService.upload(file, uploadedBy)));
    }

    @GetMapping("/{id}/metadata")
    @Operation(summary = "获取附件元数据")
    public ResponseEntity<ApiResponse<AttachmentResponse>> metadata(
            @PathVariable Long id,
            Authentication authentication) {
        UserIdentity currentUser = resolveCurrentUser(authentication);
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }

        AttachmentResponse metadata = attachmentApplicationService.getMetadata(id);
        if (!canAccessAttachment(currentUser, metadata)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "当前用户无权访问该附件"));
        }
        return ResponseEntity.ok(ApiResponse.success(metadata));
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "下载附件")
    public ResponseEntity<?> download(@PathVariable Long id,
                                      Authentication authentication) throws IOException {
        UserIdentity currentUser = resolveCurrentUser(authentication);
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }

        AttachmentResponse metadata = attachmentApplicationService.getMetadata(id);
        if (!canAccessAttachment(currentUser, metadata)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "当前用户无权下载该附件"));
        }
        Resource resource = attachmentApplicationService.loadAsResource(id);
        String encodedFileName = URLEncoder.encode(metadata.getFileName(), StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.parseMediaType(
                        metadata.getFileType() == null || metadata.getFileType().isBlank()
                                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : metadata.getFileType()
                ))
                .body(resource);
    }

    private boolean canActAsUploader(UserIdentity currentUser, Long uploadedBy) {
        return isAdmin(currentUser) || Objects.equals(currentUser.id(), uploadedBy);
    }

    /**
     * 2026-10-19 用户定案：附件（上报资料）不设细粒度下载权限——
     * 业务上只在部门之间流转，细分权限会阻塞正常查阅（如填报人看不到本部门上报材料）。
     * 规则：任何已登录用户均可查看/下载；未登录仍在入口处拦截（401）。
     */
    private boolean canAccessAttachment(UserIdentity currentUser, AttachmentResponse metadata) {
        return currentUser != null && metadata != null;
    }

    private boolean isAdmin(UserIdentity currentUser) {
        return currentUser.authorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_VICE_PRESIDENT".equals(authority)
                        || "ROLE_STRATEGY_DEPT_HEAD".equals(authority));
    }

    private UserIdentity resolveCurrentUser(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof com.sism.shared.application.dto.CurrentUser currentUser) {
            return new UserIdentity(
                    currentUser.getId(),
                    currentUser.getOrgId(),
                    currentUser.getAuthorities() == null ? java.util.List.of() : currentUser.getAuthorities()
            );
        }
        return null;
    }

    private record UserIdentity(Long id, Long orgId, Collection<? extends GrantedAuthority> authorities) {
    }
}
