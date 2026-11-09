package com.sism.main.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.main.application.BusinessImportApplicationService;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitRequest;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitResponse;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportPreviewResponse;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
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

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/imports")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Business Imports", description = "业务表格导入与自动审批接口")
public class BusinessImportController {

    private static final String SYSTEM_ADMIN_ROLE_CODE = "ROLE_SYSTEM_ADMIN";

    private final BusinessImportApplicationService businessImportApplicationService;
    /** 2026-11-05（学院指标直发改造）：战略发展部 org id，用于接口层的组织维度校验 */
    private final StrategyOrgProperties strategyOrgProperties;

    @PostMapping(value = "/strategic-tasks/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('REPORTER','STRATEGY_DEPT_HEAD','VICE_PRESIDENT','SYSTEM_ADMIN')")
    @Operation(summary = "预览战略任务指标导入", description = "填报人可解析预览；自动发起审批仅部门负责人及以上角色可用")
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
    @PreAuthorize("hasAnyRole('REPORTER','STRATEGY_DEPT_HEAD','VICE_PRESIDENT','SYSTEM_ADMIN')")
    @Operation(summary = "确认战略任务指标导入", description = "填报人可确认导入（含覆盖）；自动发起审批仅部门负责人及以上角色可用")
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
        assertStrategyOrgScope(currentUser);
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
        assertStrategyOrgScope(currentUser);
        return ResponseEntity.ok(ApiResponse.success(
                businessImportApplicationService.commit(batchId, request, currentUser)));
    }

    /**
     * 2026-11-05（学院指标直发改造）：学院子指标下发入口补一层<b>组织维度</b>校验。
     *
     * <p>为什么不能只看角色码：仓库里存在 27 个 {@code *_demo} 账号持有
     * {@code ROLE_STRATEGY_DEPT_HEAD} 但并不在战略发展部，只靠 {@code @PreAuthorize} 的角色码
     * 它们仍能调通下发接口。战略部 org id 取自配置 {@code sism.strategy.org.strategy-org-id}，
     * 与 {@code IndicatorMutationService.canInitiate} 同一范式。
     */
    private void assertStrategyOrgScope(CurrentUser currentUser) {
        if (currentUser == null || currentUser.getOrgId() == null) {
            throw new SecurityException("未登录或登录已过期");
        }
        boolean systemAdmin = currentUser.getAuthorities() != null
                && currentUser.getAuthorities().stream()
                .anyMatch(authority -> SYSTEM_ADMIN_ROLE_CODE.equals(authority.getAuthority()));
        Long strategyOrgId = strategyOrgProperties.getStrategyOrgId();
        if (!systemAdmin && (strategyOrgId == null || !Objects.equals(currentUser.getOrgId(), strategyOrgId))) {
            throw new SecurityException("学院指标下发已统一由战略发展部发起，职能部门不再向学院分发指标");
        }
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
