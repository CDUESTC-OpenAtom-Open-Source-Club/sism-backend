package com.sism.main.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.main.application.BusinessImportApplicationService;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitRequest;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitResponse;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportPreviewResponse;
import com.sism.shared.application.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Business Imports", description = "业务表格导入与自动审批接口")
public class BusinessImportController {

    private final BusinessImportApplicationService businessImportApplicationService;

    @PostMapping(value = "/strategic-tasks/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('STRATEGY_DEPT_HEAD','VICE_PRESIDENT','SYSTEM_ADMIN')")
    @Operation(summary = "预览战略任务指标导入")
    public ResponseEntity<ApiResponse<ImportPreviewResponse>> previewStrategicTasks(
            @RequestPart("file") MultipartFile file,
            @RequestParam Long cycleId,
            @RequestParam Long targetOrgId,
            @RequestParam(required = false) String sheetName,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                businessImportApplicationService.previewStrategicTasks(
                        file,
                        cycleId,
                        targetOrgId,
                        sheetName,
                        currentUser)));
    }

    @PostMapping("/strategic-tasks/{batchId}/commit")
    @PreAuthorize("hasAnyRole('STRATEGY_DEPT_HEAD','VICE_PRESIDENT','SYSTEM_ADMIN')")
    @Operation(summary = "确认战略任务指标导入")
    public ResponseEntity<ApiResponse<ImportCommitResponse>> commitStrategicTasks(
            @PathVariable String batchId,
            @RequestBody ImportCommitRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                businessImportApplicationService.commit(batchId, request, currentUser)));
    }

    @PostMapping(value = "/distribution/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('REPORTER','STRATEGY_DEPT_HEAD','VICE_PRESIDENT','SYSTEM_ADMIN')")
    @Operation(summary = "预览学院子指标导入")
    public ResponseEntity<ApiResponse<ImportPreviewResponse>> previewDistribution(
            @RequestPart("file") MultipartFile file,
            @RequestParam Long cycleId,
            @RequestParam Long targetCollegeOrgId,
            @RequestParam(required = false) Long sourceOrgId,
            @RequestParam(required = false) String sheetName,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                businessImportApplicationService.previewDistribution(
                        file,
                        cycleId,
                        targetCollegeOrgId,
                        sourceOrgId,
                        sheetName,
                        currentUser)));
    }

    @PostMapping("/distribution/{batchId}/commit")
    @PreAuthorize("hasAnyRole('REPORTER','STRATEGY_DEPT_HEAD','VICE_PRESIDENT','SYSTEM_ADMIN')")
    @Operation(summary = "确认学院子指标导入")
    public ResponseEntity<ApiResponse<ImportCommitResponse>> commitDistribution(
            @PathVariable String batchId,
            @RequestBody ImportCommitRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                businessImportApplicationService.commit(batchId, request, currentUser)));
    }

    @GetMapping("/{batchId}")
    @Operation(summary = "查询导入批次预览")
    public ResponseEntity<ApiResponse<ImportPreviewResponse>> getImportPreview(
            @PathVariable String batchId,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                businessImportApplicationService.getPreview(batchId, currentUser)));
    }
}
