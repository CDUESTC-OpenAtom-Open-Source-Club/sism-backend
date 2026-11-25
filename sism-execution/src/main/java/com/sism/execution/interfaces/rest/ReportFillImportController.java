package com.sism.execution.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.execution.application.ReportFillImportApplicationService;
import com.sism.execution.interfaces.dto.ReportFillImportDtos.CommitRequest;
import com.sism.execution.interfaces.dto.ReportFillImportDtos.CommitResponse;
import com.sism.execution.interfaces.dto.ReportFillImportDtos.PreviewResponse;
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
 * 2026-11-25（二级学院填报导入）：填报页「导入 / 批量导入」接口。
 *
 * <p>与战略发展部的「学院子指标下发」导入（/imports/distribution）不是一回事：
 * 这里的操作者是二级学院填报人，导入的内容只是自己学院已下发指标的
 * 「自评等级 + 进度说明」，落库结果是一份月度草稿，不触发任何审批。
 */
@RestController
@RequestMapping("/api/v1/imports/report-fill")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Report Fill Imports", description = "二级学院填报导入（自评等级 + 进度说明）")
public class ReportFillImportController {

    /** 与 ReportController#REPORT_FILL_ACCESS 保持一致：只有填报人（及系统管理员）能写填报内容 */
    private static final String REPORT_FILL_ACCESS = "hasAnyRole('REPORTER','SYSTEM_ADMIN')";

    private final ReportFillImportApplicationService reportFillImportApplicationService;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(REPORT_FILL_ACCESS)
    @Operation(summary = "预览填报导入", description = "解析 Excel 并按归口职能部门分组，返回可写入的指标行；学院取自登录身份")
    public ResponseEntity<ApiResponse<PreviewResponse>> preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam Long cycleId,
            @RequestParam String reportMonth,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                reportFillImportApplicationService.preview(currentUser, file, cycleId, reportMonth)));
    }

    @PostMapping("/{batchId}/commit")
    @PreAuthorize(REPORT_FILL_ACCESS)
    @Operation(summary = "确认填报导入", description = "把预览通过的指标行写入月度草稿；不会发起审批，是否上报仍由「一键提交」决定")
    public ResponseEntity<ApiResponse<CommitResponse>> commit(
            @PathVariable String batchId,
            @RequestBody(required = false) CommitRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                reportFillImportApplicationService.commit(currentUser, batchId, request)));
    }
}
