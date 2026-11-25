package com.sism.main.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.main.application.ReportFillImportApplicationService;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitRequest;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitResponse;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillPreviewResponse;
import com.sism.shared.application.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 2026-11-25（指标填报导入式上报）：REST 端点。
 *
 * <p>与既有的 {@code /api/v1/imports/**}（导入<b>下发</b>）路径隔离：
 * 本组端点落在 {@code /api/v1/imports/fill/**}，语义是「把 Excel 里的填报内容写进本月月报」。
 *
 * <p>覆盖两条上报流向（标准模板 04/05/06）：
 * 职能部门 → 战略发展部（04）；学院 → 归口职能部门（05 单点 / 06 批量）。
 */
@RestController
@RequestMapping("/api/v1/imports/fill")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Report Fill Imports", description = "指标填报导入式上报（Excel 批量填报）")
public class ReportFillImportController {

    /**
     * 2026-10-24 用户定案：填报内容写入只属于填报人角色（与 {@code ReportController} 的
     * {@code REPORT_FILL_ACCESS} 同口径——审核人只审不填）。
     *
     * <p>2026-11-25：去掉 SYSTEM_ADMIN——服务端要求登录人组织是二级学院或职能部门，
     * 管理员（admin 组织）永远走不通，列在这里只会让人误以为「管理员可代填」。
     */
    private static final String REPORT_FILL_ACCESS = "hasAnyRole('REPORTER')";

    private final ReportFillImportApplicationService reportFillImportApplicationService;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(REPORT_FILL_ACCESS)
    @Operation(summary = "预览填报导入",
            description = "解析填报导入 Excel：校验责任部门（须与登录身份一致）、目标部门/上报目标、"
                    + "按目标部门拆线、定位已下发指标、自评等级白名单校验；"
                    + "返回逐组预览（将提交 / 将跳过 / 错误组）。"
                    + "mode=single（单归口入口）时必须给 targetOrgId，只提交该线；"
                    + "mode=all（默认）提交文件内出现的全部线")
    public ResponseEntity<ApiResponse<ReportFillPreviewResponse>> preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam Long cycleId,
            @RequestParam String reportMonth,
            @RequestParam(required = false, defaultValue = "all") String mode,
            @RequestParam(required = false) Long targetOrgId,
            @RequestParam(required = false) String sheetName,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                reportFillImportApplicationService.preview(
                        file,
                        cycleId,
                        reportMonth,
                        mode,
                        targetOrgId,
                        sheetName,
                        currentUser)));
    }

    @PostMapping("/{batchId}/commit")
    @PreAuthorize(REPORT_FILL_ACCESS)
    @Operation(summary = "确认填报导入",
            description = "逐条上报线独立事务：落填报明细（自评等级 + 进度说明）并提交该线的上报审批；"
                    + "一条线失败不影响其他线")
    public ResponseEntity<ApiResponse<ReportFillCommitResponse>> commit(
            @PathVariable String batchId,
            @RequestBody ReportFillCommitRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                reportFillImportApplicationService.commit(batchId, request, currentUser)));
    }
}
