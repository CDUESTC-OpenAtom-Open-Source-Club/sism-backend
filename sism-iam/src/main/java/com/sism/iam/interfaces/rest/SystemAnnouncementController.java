package com.sism.iam.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.iam.application.dto.AnnouncementResponse;
import com.sism.iam.application.dto.CreateAnnouncementRequest;
import com.sism.iam.application.dto.UpdateAnnouncementRequest;
import com.sism.iam.application.service.SystemAnnouncementService;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.shared.application.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/announcements")
@RequiredArgsConstructor
@Tag(name = "系统公告管理", description = "系统维护公告的发布、撤回和查询接口")
public class SystemAnnouncementController {

    private final SystemAnnouncementService announcementService;
    private final OrganizationRepository organizationRepository;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "创建公告", description = "创建一条系统维护公告（草稿状态）")
    public ResponseEntity<ApiResponse<AnnouncementResponse>> create(
            @RequestBody CreateAnnouncementRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }
        ResponseEntity<ApiResponse<AnnouncementResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        AnnouncementResponse response = announcementService.create(request, currentUser.getId());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "编辑公告", description = "编辑草稿或已撤回公告，已发布公告不允许修改")
    public ResponseEntity<ApiResponse<AnnouncementResponse>> update(
            @PathVariable Long id,
            @RequestBody UpdateAnnouncementRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(2000, "未登录"));
        }
        ResponseEntity<ApiResponse<AnnouncementResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        AnnouncementResponse response = announcementService.update(id, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "发布公告", description = "立即发布公告，发布后所有活跃用户将收到站内通知和邮件")
    public ResponseEntity<ApiResponse<AnnouncementResponse>> publish(
            @PathVariable Long id,
            @AuthenticationPrincipal CurrentUser currentUser) {
        ResponseEntity<ApiResponse<AnnouncementResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        AnnouncementResponse response = announcementService.publish(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "撤回公告", description = "撤回已发布的公告")
    public ResponseEntity<ApiResponse<AnnouncementResponse>> withdraw(
            @PathVariable Long id,
            @AuthenticationPrincipal CurrentUser currentUser) {
        ResponseEntity<ApiResponse<AnnouncementResponse>> denied = denyIfNoAdminOrgAccess(currentUser);
        if (denied != null) {
            return denied;
        }
        AnnouncementResponse response = announcementService.withdraw(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查看公告详情", description = "根据ID查看公告详情")
    public ResponseEntity<ApiResponse<AnnouncementResponse>> getById(@PathVariable Long id) {
        AnnouncementResponse response = announcementService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "公告列表", description = "分页查询公告列表，可按状态筛选")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AnnouncementResponse> announcements = announcementService.list(status, page, size);

        Map<String, Object> response = new HashMap<>();
        response.put("content", announcements.getContent());
        response.put("totalElements", announcements.getTotalElements());
        response.put("totalPages", announcements.getTotalPages());
        response.put("number", announcements.getNumber());
        response.put("size", announcements.getSize());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/public")
    @Operation(summary = "公开公告列表", description = "公开查询已发布公告列表，供登录页等未登录场景使用")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listPublished(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        Page<AnnouncementResponse> announcements = announcementService.listPublished(page, size);

        Map<String, Object> response = new HashMap<>();
        response.put("content", announcements.getContent());
        response.put("totalElements", announcements.getTotalElements());
        response.put("totalPages", announcements.getTotalPages());
        response.put("number", announcements.getNumber());
        response.put("size", announcements.getSize());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private boolean hasAdminOrgAccess(CurrentUser currentUser) {
        if (currentUser == null || currentUser.getOrgId() == null) {
            return false;
        }

        return organizationRepository.findById(currentUser.getOrgId())
                .map(org -> org.getType() == OrgType.admin)
                .orElse(false);
    }

    private <T> ResponseEntity<ApiResponse<T>> denyIfNoAdminOrgAccess(CurrentUser currentUser) {
        if (hasAdminOrgAccess(currentUser)) {
            return null;
        }

        return ResponseEntity.status(403).body(ApiResponse.error(403, "无权限访问"));
    }
}
