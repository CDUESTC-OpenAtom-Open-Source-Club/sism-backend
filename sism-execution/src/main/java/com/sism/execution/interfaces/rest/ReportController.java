package com.sism.execution.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.common.PageResult;
import com.sism.exception.ResourceNotFoundException;
import com.sism.execution.application.ReportApplicationService;
import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.ReportOrgType;
import com.sism.execution.interfaces.dto.*;
import com.sism.shared.application.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ReportController - 计划报告API控制器
 * 提供计划报告管理相关的REST API端点
 */
@RestController("executionReportController")
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "计划报告", description = "计划报告管理接口")
public class ReportController {

    private static final String REPORT_WRITE_ACCESS =
            "hasAnyRole('REPORTER','APPROVER','STRATEGY_DEPT_HEAD','VICE_PRESIDENT','SYSTEM_ADMIN')";
    /**
     * 2026-10-24 用户定案：填报内容（创建草稿/写入明细）只属于填报人角色。
     * 此前复用 REPORT_WRITE_ACCESS，APPROVER（部门审核人）也能写填报——
     * 界面上「填报/编辑」按钮同步收紧为仅填报人可见，此处为服务端兜底。
     */
    private static final String REPORT_FILL_ACCESS =
            "hasAnyRole('REPORTER','SYSTEM_ADMIN')";
    private static final String REPORT_APPROVAL_ACCESS =
            "hasAnyRole('APPROVER','STRATEGY_DEPT_HEAD','VICE_PRESIDENT','SYSTEM_ADMIN')";

    private final ReportApplicationService reportApplicationService;
    /** 2026-10-24：审批链上的组织可查阅报告内容（否则审批详情内容区空白） */
    private final com.sism.execution.domain.report.ReportApprovalChainAccessQuery
            reportApprovalChainAccessQuery;

    // ==================== Report CRUD Operations ====================

    @PostMapping
    @Operation(summary = "创建月度报告（草稿）", description = "创建一个新的月度进度报告，初始状态为草稿")
    @PreAuthorize(REPORT_FILL_ACCESS)
    public ResponseEntity<ApiResponse<PlanReportResponse>> createReport(
            @Valid @RequestBody CreatePlanReportRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        ensureCanAccessOrg(currentUser, request.getReportOrgId());
        PlanReport report = reportApplicationService.createReport(
                request.getReportMonth(),
                request.getReportOrgId(),
                request.getReportOrgType(),
                request.getPlanId(),
                requireCurrentUserId(currentUser)
        );
        return ResponseEntity.ok(ApiResponse.success(PlanReportResponse.fromEntity(report)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新报告内容", description = "更新报告的内容、摘要、进度、问题和下一步计划等信息")
    @PreAuthorize(REPORT_FILL_ACCESS)
    public ResponseEntity<ApiResponse<PlanReportResponse>> updateReport(
            @Parameter(description = "报告ID") @PathVariable Long id,
            @Valid @RequestBody UpdatePlanReportRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        requireReportWithPermission(id, currentUser);
        PlanReport report =
                request.getIndicatorDetails() != null && !request.getIndicatorDetails().isEmpty()
                        ? reportApplicationService.updateReportBatch(
                                id,
                                request.getTitle(),
                                request.getContent(),
                                request.getSummary(),
                                request.getProgress(),
                                request.getIssues(),
                                request.getNextPlan(),
                                requireCurrentUserId(currentUser),
                                request.getIndicatorDetails()
                        )
                        : reportApplicationService.updateReport(
                                id,
                                request.getTitle(),
                                request.getIndicatorId(),
                                request.getContent(),
                                request.getSummary(),
                                request.getProgress(),
                                request.getIssues(),
                                request.getNextPlan(),
                                requireCurrentUserId(currentUser)
                        );
        return ResponseEntity.ok(ApiResponse.success(PlanReportResponse.fromEntity(report)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据ID查询报告详情", description = "获取指定报告的完整信息")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<PlanReportResponse>> getReportById(
            @Parameter(description = "报告ID") @PathVariable Long id,
            @AuthenticationPrincipal CurrentUser currentUser) {
        PlanReport report = requireReportWithPermission(id, currentUser);
        // 2026-10-27 用户定案：草稿按 ID 直查仅「填报组织自身 + 系统管理员」；
        // 链上审批角色在报告提交进入流程后经审批链可读（ensureCanAccessReport 既有逻辑）。
        if (PlanReport.STATUS_DRAFT.equals(report.getStatus()) && !isSystemAdmin(currentUser)) {
            Long viewerOrgId = requireCurrentOrgId(currentUser);
            if (report.getReportOrgId() == null || !report.getReportOrgId().equals(viewerOrgId)) {
                throw new AccessDeniedException("未提交的填报内容仅填报组织可见");
            }
        }
        return ResponseEntity.ok(ApiResponse.success(PlanReportResponse.fromEntity(report)));
    }

    // ==================== Report Action Operations ====================

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交报告", description = "将草稿状态的报告提交审批")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<PlanReportResponse>> submitReport(
            @Parameter(description = "报告ID") @PathVariable Long id,
            @AuthenticationPrincipal CurrentUser currentUser) {
        requireReportWithPermission(id, currentUser);
        PlanReport report = reportApplicationService.submitReport(id, requireCurrentUserId(currentUser), currentUser);
        return ResponseEntity.ok(ApiResponse.success(PlanReportResponse.fromEntity(report)));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "审批通过报告", description = "审批通过已提交的报告")
    @PreAuthorize(REPORT_APPROVAL_ACCESS)
    public ResponseEntity<ApiResponse<PlanReportResponse>> approveReport(
            @Parameter(description = "报告ID") @PathVariable Long id,
            @AuthenticationPrincipal CurrentUser currentUser) {
        requireReportWithPermission(id, currentUser);
        PlanReport report = reportApplicationService.approveReport(id, requireCurrentUserId(currentUser), currentUser);
        return ResponseEntity.ok(ApiResponse.success(PlanReportResponse.fromEntity(report)));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "驳回报告", description = "驳回已提交的报告，并提供驳回理由")
    @PreAuthorize(REPORT_APPROVAL_ACCESS)
    public ResponseEntity<ApiResponse<PlanReportResponse>> rejectReport(
            @Parameter(description = "报告ID") @PathVariable Long id,
            @Valid @RequestBody RejectPlanReportRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        requireReportWithPermission(id, currentUser);
        PlanReport report = reportApplicationService.rejectReport(id, requireCurrentUserId(currentUser), request.getReason(), currentUser);
        return ResponseEntity.ok(ApiResponse.success(PlanReportResponse.fromEntity(report)));
    }

    // ==================== Report Query Operations ====================

    @GetMapping
    @Operation(summary = "分页查询所有报告", description = "获取所有有效的报告，支持分页")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<PageResult<PlanReportSimpleResponse>>> getAllReports(
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal CurrentUser currentUser) {
        Page<PlanReport> reportPage = isAdmin(currentUser)
                ? reportApplicationService.findAllActiveReports(page, size)
                : reportApplicationService.findReportsByOrgId(requireCurrentOrgId(currentUser), page, size);
        List<PlanReportSimpleResponse> responses = reportPage.getContent().stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        PageResult<PlanReportSimpleResponse> pageResult = PageResult.of(
                responses,
                reportPage.getTotalElements(),
                reportPage.getNumber(),
                reportPage.getSize()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResult));
    }

    @GetMapping("/search")
    @Operation(summary = "多条件查询报告", description = "根据多种条件组合查询报告，支持分页")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<PageResult<PlanReportSimpleResponse>>> searchReports(
            @Valid PlanReportQueryRequest queryRequest,
            @AuthenticationPrincipal CurrentUser currentUser) {
        Page<PlanReport> reportPage = isAdmin(currentUser)
                ? reportApplicationService.findReportsByConditions(queryRequest)
                : reportApplicationService.findReportsByConditionsForOrg(queryRequest, requireCurrentOrgId(currentUser));
        List<PlanReportSimpleResponse> responses = reportPage.getContent().stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        PageResult<PlanReportSimpleResponse> pageResult = PageResult.of(
                responses,
                reportPage.getTotalElements(),
                reportPage.getNumber(),
                reportPage.getSize()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResult));
    }

    @GetMapping("/org/{orgId}")
    @Operation(summary = "根据组织ID查询报告（列表）", description = "获取指定组织的所有报告列表")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<List<PlanReportSimpleResponse>>> getReportsByOrgId(
            @Parameter(description = "组织ID") @PathVariable Long orgId,
            @AuthenticationPrincipal CurrentUser currentUser) {
        ensureCanAccessOrg(currentUser, orgId);
        List<PlanReport> reports = reportApplicationService.findReportsByOrgId(orgId);
        List<PlanReportSimpleResponse> responses = reports.stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/org/{orgId}/page")
    @Operation(summary = "根据组织ID分页查询报告", description = "获取指定组织的报告，支持分页")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<PageResult<PlanReportSimpleResponse>>> getReportsByOrgIdPaginated(
            @Parameter(description = "组织ID") @PathVariable Long orgId,
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal CurrentUser currentUser) {
        ensureCanAccessOrg(currentUser, orgId);
        Page<PlanReport> reportPage = reportApplicationService.findReportsByOrgId(orgId, page, size);
        List<PlanReportSimpleResponse> responses = reportPage.getContent().stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        PageResult<PlanReportSimpleResponse> pageResult = PageResult.of(
                responses,
                reportPage.getTotalElements(),
                reportPage.getNumber(),
                reportPage.getSize()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResult));
    }

    @GetMapping("/month/{month}")
    @Operation(summary = "根据月份查询报告", description = "获取指定月份的所有报告")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<List<PlanReportSimpleResponse>>> getReportsByMonth(
            @Parameter(description = "月份，格式：yyyy-MM") @PathVariable String month,
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<PlanReport> reports = isAdmin(currentUser)
                ? reportApplicationService.findReportsByMonth(month)
                : reportApplicationService.findReportsByMonthAndOrgId(month, requireCurrentOrgId(currentUser));
        List<PlanReportSimpleResponse> responses = reports.stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "根据状态查询报告（列表）", description = "获取指定状态的所有报告列表")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<List<PlanReportSimpleResponse>>> getReportsByStatus(
            @Parameter(description = "报告状态：DRAFT/SUBMITTED/APPROVED/REJECTED") @PathVariable String status,
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<PlanReport> reports = isAdmin(currentUser)
                ? reportApplicationService.findReportsByStatus(status)
                : reportApplicationService.findReportsByStatusForOrg(status, requireCurrentOrgId(currentUser));
        List<PlanReportSimpleResponse> responses = reports.stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/status/{status}/page")
    @Operation(summary = "根据状态分页查询报告", description = "获取指定状态的报告，支持分页")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<PageResult<PlanReportSimpleResponse>>> getReportsByStatusPaginated(
            @Parameter(description = "报告状态：DRAFT/SUBMITTED/APPROVED/REJECTED") @PathVariable String status,
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal CurrentUser currentUser) {
        Page<PlanReport> reportPage = isAdmin(currentUser)
                ? reportApplicationService.findReportsByStatus(status, page, size)
                : reportApplicationService.findReportsByStatusForOrg(status, requireCurrentOrgId(currentUser), page, size);
        List<PlanReportSimpleResponse> responses = reportPage.getContent().stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        PageResult<PlanReportSimpleResponse> pageResult = PageResult.of(
                responses,
                reportPage.getTotalElements(),
                reportPage.getNumber(),
                reportPage.getSize()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResult));
    }

    @GetMapping("/pending")
    @Operation(summary = "查询待审批的报告", description = "获取所有状态为SUBMITTED的待审批报告")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<List<PlanReportSimpleResponse>>> getPendingReports(
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<PlanReport> reports = isAdmin(currentUser)
                ? reportApplicationService.findPendingReports()
                : reportApplicationService.findPendingReportsForOrg(requireCurrentOrgId(currentUser));
        List<PlanReportSimpleResponse> responses = reports.stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/org-type/{orgType}")
    @Operation(summary = "根据组织类型查询报告", description = "获取指定组织类型的所有报告")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<List<PlanReportSimpleResponse>>> getReportsByOrgType(
            @Parameter(description = "组织类型：ADMIN/FUNCTIONAL/ACADEMIC") @PathVariable ReportOrgType orgType,
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<PlanReport> reports = isAdmin(currentUser)
                ? reportApplicationService.findReportsByOrgType(orgType)
                : reportApplicationService.findReportsByOrgTypeForOrg(orgType, requireCurrentOrgId(currentUser));
        List<PlanReportSimpleResponse> responses = reports.stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/plan/{planId}")
    @Operation(summary = "根据计划ID查询报告", description = "获取关联到指定计划的所有报告")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<List<PlanReportSimpleResponse>>> getReportsByPlanId(
            @Parameter(description = "计划ID") @PathVariable Long planId,
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<PlanReport> reports = isAdmin(currentUser)
                ? reportApplicationService.findReportsByPlanId(planId)
                : reportApplicationService.findReportsByPlanIdForOrg(planId, requireCurrentOrgId(currentUser));

        // 2026-10-27 用户定案：未提交的草稿仅「填报组织自身」可见——
        // 上级/审批链角色（如战略发展部负责人、分管校领导）在草稿提交进入审核之前，
        // 看到的应该都是上一次已上报的数据，而不是填报人正在编辑的草稿。
        // 2026-10-27 豁免收窄：isAdmin 含链上审批角色（上报链终审/分管校领导节点授权角色），
        // 若以它豁免草稿过滤，终审人在草稿期就能透视填报内容——豁免仅保留真系统管理员。
        if (!isSystemAdmin(currentUser)) {
            Long viewerOrgId = requireCurrentOrgId(currentUser);
            reports = reports.stream()
                    .filter(report -> !PlanReport.STATUS_DRAFT.equals(report.getStatus())
                            || (report.getReportOrgId() != null
                                    && report.getReportOrgId().equals(viewerOrgId)))
                    .toList();
        }

        List<PlanReportSimpleResponse> responses = reports.stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /**
     * 2026-10-28：导出专用——批量返回每个指标「当前登录人可见」的最新一轮填报附件。
     * 可见性口径与页面完全一致：草稿只对填报组织自身可见，上级拿到的是上一轮已上报快照。
     */
    @PostMapping("/indicator-attachments/latest")
    @Operation(summary = "批量查询指标最新可见填报附件",
            description = "导出用：按当前登录人可见范围返回每个指标最新一轮填报的附件")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<List<IndicatorLatestAttachmentResponse>>> getLatestVisibleIndicatorAttachments(
            @Valid @RequestBody IndicatorAttachmentQueryRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        boolean systemAdmin = isSystemAdmin(currentUser);
        Long viewerOrgId = systemAdmin ? null : requireCurrentOrgId(currentUser);
        List<IndicatorLatestAttachmentResponse> summaries =
                reportApplicationService.findLatestVisibleIndicatorAttachments(
                        request.getIndicatorIds(), systemAdmin, viewerOrgId);
        return ResponseEntity.ok(ApiResponse.success(summaries));
    }

    @GetMapping("/count/status/{status}")
    @Operation(summary = "统计指定状态的报告数量", description = "获取指定状态的报告总数")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<Long>> countReportsByStatus(
            @Parameter(description = "报告状态") @PathVariable String status,
            @AuthenticationPrincipal CurrentUser currentUser) {
        long count = isAdmin(currentUser)
                ? reportApplicationService.countReportsByStatus(status)
                : reportApplicationService.countReportsByStatusForOrg(status, requireCurrentOrgId(currentUser));
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @GetMapping("/month/{month}/org/{orgId}")
    @Operation(summary = "根据月份和组织ID查询报告", description = "获取指定月份和组织的所有报告")
    @PreAuthorize(REPORT_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<List<PlanReportSimpleResponse>>> getReportsByMonthAndOrgId(
            @Parameter(description = "月份，格式：yyyy-MM") @PathVariable String month,
            @Parameter(description = "组织ID") @PathVariable Long orgId,
            @AuthenticationPrincipal CurrentUser currentUser) {
        ensureCanAccessOrg(currentUser, orgId);
        List<PlanReport> reports = reportApplicationService.findReportsByMonthAndOrgId(month, orgId);
        List<PlanReportSimpleResponse> responses = reports.stream()
                .map(PlanReportSimpleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    private PlanReport requireReportWithPermission(Long id, CurrentUser currentUser) {
        PlanReport report = reportApplicationService.findReportById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Report", id));
        ensureCanAccessReport(currentUser, report);
        return report;
    }

    private List<PlanReport> filterReportsByPermission(List<PlanReport> reports, CurrentUser currentUser) {
        if (reports == null || reports.isEmpty() || isAdmin(currentUser)) {
            return reports;
        }
        Long currentOrgId = requireCurrentOrgId(currentUser);
        return reports.stream()
                .filter(report -> report != null && Objects.equals(report.getReportOrgId(), currentOrgId))
                .collect(Collectors.toList());
    }

    private void ensureCanAccessReport(CurrentUser currentUser, PlanReport report) {
        if (report == null || isAdmin(currentUser)) {
            return;
        }
        Long currentOrgId = requireCurrentOrgId(currentUser);
        if (Objects.equals(report.getReportOrgId(), currentOrgId)) {
            return;
        }
        // 2026-10-24（上报内容可查阅修复）：审批方组织（上报链上的申请方/审批方席位）
        // 必须能看到报告快照——否则审批详情「查看详情」内容区空白，历史上报不可查阅。
        // 写操作另有 @PreAuthorize(REPORT_WRITE_ACCESS) 与组织校验把守，此处仅放行读。
        if (reportApprovalChainAccessQuery.isOrgOnApprovalChain(report.getId(), currentOrgId)) {
            return;
        }
        throw new AccessDeniedException("不能操作其他组织的报告");
    }

    private void ensureCanAccessOrg(CurrentUser currentUser, Long orgId) {
        if (isAdmin(currentUser)) {
            return;
        }
        Long currentOrgId = requireCurrentOrgId(currentUser);
        if (!Objects.equals(currentOrgId, orgId)) {
            throw new AccessDeniedException("不能访问其他组织的报告");
        }
    }

    private boolean isAdmin(CurrentUser currentUser) {
        if (currentUser == null || currentUser.getAuthorities() == null) {
            return false;
        }
        return currentUser.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority)
                        || "ROLE_SYSTEM_ADMIN".equals(authority)
                        || "ROLE_VICE_PRESIDENT".equals(authority)
                        || "ROLE_STRATEGY_DEPT_HEAD".equals(authority));
    }

    /**
     * 2026-10-27：真系统管理员。与 isAdmin 的区别是剔除战略发展部负责人/分管校领导——
     * 这两个角色是上报审批链的节点授权角色（终审/分管校领导节点），属「链上审批人」，
     * 其内容可见性应由审批链授予（报告进入流程后），而不是草稿期的全局透视。
     */
    private boolean isSystemAdmin(CurrentUser currentUser) {
        if (currentUser == null || currentUser.getAuthorities() == null) {
            return false;
        }
        return currentUser.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority)
                        || "ROLE_SYSTEM_ADMIN".equals(authority));
    }

    private Long requireCurrentOrgId(CurrentUser currentUser) {
        if (currentUser == null || currentUser.getOrgId() == null || currentUser.getOrgId() <= 0) {
            throw new AccessDeniedException("当前用户缺少组织信息");
        }
        return currentUser.getOrgId();
    }

    private Long requireCurrentUserId(CurrentUser currentUser) {
        if (currentUser == null || currentUser.getId() == null || currentUser.getId() <= 0) {
            throw new AccessDeniedException("当前用户未登录或无效");
        }
        return currentUser.getId();
    }

}
