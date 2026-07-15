package com.sism.alert.interfaces.rest;

import com.sism.alert.application.AlertAccessService;
import com.sism.alert.application.AlertApplicationService;
import com.sism.alert.domain.Alert;
import com.sism.alert.interfaces.dto.AlertResponse;
import com.sism.alert.interfaces.dto.AlertRequest;
import com.sism.alert.interfaces.dto.ManualAlertLevelRequest;
import com.sism.alert.interfaces.dto.AlertStatsDTO;
import com.sism.alert.interfaces.dto.ResolveAlertRequest;
import com.sism.common.ApiResponse;
import com.sism.common.PageResult;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.shared.domain.exception.AuthorizationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AlertController - 预警管理API控制器
 * 提供预警相关的REST API端点
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@Tag(name = "预警管理", description = "预警和警告管理接口")
public class AlertController {

    private static final int DEFAULT_PAGE = 0;
    private static final int MAX_SIZE = 100;
    private static final Long STRATEGIC_DEPT_ORG_ID = 35L;

    private final AlertApplicationService alertApplicationService;
    private final AlertAccessService alertAccessService;

    // ==================== Create ====================

    @PostMapping
    @Operation(summary = "创建预警", description = "创建新的预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AlertResponse>> createAlert(
            @Valid @RequestBody AlertRequest request,
            Authentication authentication
    ) {
        alertAccessService.ensureIndicatorAccess(request.getIndicatorId(), authentication);
        Alert alert = alertApplicationService.createAlert(
                request.getIndicatorId(),
                request.getRuleId(),
                request.getWindowId(),
                request.getSeverity(),
                request.getActualPercent(),
                request.getExpectedPercent(),
                request.getGapPercent(),
                request.getDetailJson()
        );
        return ResponseEntity.ok(ApiResponse.success(AlertResponse.fromEntity(alert)));
    }

    @PostMapping("/{id}/trigger")
    @Operation(summary = "触发预警", description = "手动触发预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AlertResponse>> triggerAlert(
            @PathVariable Long id,
            Authentication authentication
    ) {
        alertAccessService.requireAccessibleAlert(id, authentication);
        Alert triggeredAlert = alertApplicationService.triggerAlert(id);
        return ResponseEntity.ok(ApiResponse.success(AlertResponse.fromEntity(triggeredAlert)));
    }

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "获取所有预警", description = "查询所有预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AlertResponse>>> getAllAlerts(Authentication authentication) {
        List<Alert> alerts = alertAccessService.getAccessibleAlerts(authentication);
        return ResponseEntity.ok(ApiResponse.success(alerts.stream().map(AlertResponse::fromEntity).toList()));
    }

    @GetMapping("/page")
    @Operation(summary = "分页获取所有预警", description = "分页查询所有预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResult<AlertResponse>>> getAllAlertsPage(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var result = alertAccessService.getAccessibleAlerts(authentication, toPageRequest(page, size))
                .map(AlertResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success(PageResult.of(result)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取预警", description = "根据ID查询预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AlertResponse>> getAlertById(
            @PathVariable Long id,
            Authentication authentication
    ) {
        return alertApplicationService.getAlertById(id)
                .map(alert -> {
                    alertAccessService.validateAlertAccess(alert, authentication);
                    return ResponseEntity.ok(ApiResponse.success(AlertResponse.fromEntity(alert)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "按状态获取预警", description = "按状态查询预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AlertResponse>>> getAlertsByStatus(
            @PathVariable String status,
            Authentication authentication
    ) {
        List<Alert> alerts = alertAccessService.getAccessibleAlertsByStatus(status, authentication);
        return ResponseEntity.ok(ApiResponse.success(alerts.stream().map(AlertResponse::fromEntity).toList()));
    }

    @GetMapping("/status/{status}/page")
    @Operation(summary = "分页按状态获取预警", description = "按状态分页查询预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResult<AlertResponse>>> getAlertsByStatusPage(
            @PathVariable String status,
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var result = alertAccessService.getAccessibleAlertsByStatus(status, authentication, toPageRequest(page, size))
                .map(AlertResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success(PageResult.of(result)));
    }

    @GetMapping("/severity/{severity}")
    @Operation(summary = "按严重程度获取预警", description = "按严重程度查询预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AlertResponse>>> getAlertsBySeverity(
            @PathVariable String severity,
            Authentication authentication
    ) {
        List<Alert> alerts = alertAccessService.getAccessibleAlertsBySeverity(severity, authentication);
        return ResponseEntity.ok(ApiResponse.success(alerts.stream().map(AlertResponse::fromEntity).toList()));
    }

    @GetMapping("/severity/{severity}/page")
    @Operation(summary = "分页按严重程度获取预警", description = "按严重程度分页查询预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResult<AlertResponse>>> getAlertsBySeverityPage(
            @PathVariable String severity,
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var result = alertAccessService.getAccessibleAlertsBySeverity(severity, authentication, toPageRequest(page, size))
                .map(AlertResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success(PageResult.of(result)));
    }

    @GetMapping("/indicator/{indicatorId}")
    @Operation(summary = "按指标获取预警", description = "按指标ID查询预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AlertResponse>>> getAlertsByIndicator(
            @PathVariable Long indicatorId,
            Authentication authentication
    ) {
        List<Alert> alerts = alertAccessService.getAccessibleAlertsByIndicator(indicatorId, authentication);
        return ResponseEntity.ok(ApiResponse.success(alerts.stream().map(AlertResponse::fromEntity).toList()));
    }

    @GetMapping("/indicator/{indicatorId}/page")
    @Operation(summary = "分页按指标获取预警", description = "按指标ID分页查询预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResult<AlertResponse>>> getAlertsByIndicatorPage(
            @PathVariable Long indicatorId,
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var result = alertAccessService.getAccessibleAlertsByIndicator(indicatorId, authentication, toPageRequest(page, size))
                .map(AlertResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success(PageResult.of(result)));
    }

    @GetMapping("/unresolved")
    @Operation(summary = "获取未解决的预警", description = "查询所有未解决的预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AlertResponse>>> getUnresolvedAlerts(Authentication authentication) {
        List<Alert> alerts = alertAccessService.getAccessibleUnresolvedAlerts(authentication);
        return ResponseEntity.ok(ApiResponse.success(alerts.stream().map(AlertResponse::fromEntity).toList()));
    }

    @GetMapping("/unresolved/page")
    @Operation(summary = "分页获取未解决的预警", description = "分页查询所有未解决的预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResult<AlertResponse>>> getUnresolvedAlertsPage(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var result = alertAccessService.getAccessibleUnresolvedAlerts(authentication, toPageRequest(page, size))
                .map(AlertResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success(PageResult.of(result)));
    }

    @GetMapping("/search")
    @Operation(summary = "分页筛选预警", description = "支持按状态和严重程度分页筛选预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResult<AlertResponse>>> searchAlerts(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var result = alertAccessService.searchAccessibleAlerts(status, severity, authentication, toPageRequest(page, size))
                .map(AlertResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success(PageResult.of(result)));
    }

    @GetMapping("/events/unclosed")
    @Operation(summary = "获取未关闭的预警事件", description = "查询所有未关闭的预警事件(前端兼容性)")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AlertResponse>>> getUnclosedAlertEvents(Authentication authentication) {
        // Alias for /unresolved to match frontend API expectations
        List<Alert> alerts = alertAccessService.getAccessibleUnresolvedAlerts(authentication);
        return ResponseEntity.ok(ApiResponse.success(alerts.stream().map(AlertResponse::fromEntity).toList()));
    }

    @GetMapping("/manual-levels")
    @Operation(summary = "批量获取指标手动预警等级", description = "按指标ID查询战略任务管理手动设置的当前预警等级")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<Long, String>>> getManualAlertLevels(
            @RequestParam("indicatorIds") List<Long> indicatorIds,
            Authentication authentication
    ) {
        for (Long indicatorId : indicatorIds) {
            alertAccessService.ensureIndicatorAccess(indicatorId, authentication);
        }
        return ResponseEntity.ok(ApiResponse.success(alertApplicationService.getCurrentManualAlertLevels(indicatorIds)));
    }

    // ==================== Update ====================

    @PutMapping("/indicator/{indicatorId}/manual-level")
    @Operation(summary = "设置指标手动预警等级", description = "战略发展部在战略任务管理表格中手动设置或取消预警等级")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AlertResponse>> setManualAlertLevel(
            @PathVariable Long indicatorId,
            @Valid @RequestBody ManualAlertLevelRequest request,
            Authentication authentication
    ) {
        ensureManualAlertWriteAccess(authentication);
        alertAccessService.ensureIndicatorAccess(indicatorId, authentication);
        Long handledBy = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                alertApplicationService.setManualAlertLevel(indicatorId, request.getSeverity(), handledBy)
                        .map(AlertResponse::fromEntity)
                        .orElse(null)
        ));
    }

    private void ensureManualAlertWriteAccess(Authentication authentication) {
        if (authentication == null) {
            throw new AuthorizationException("当前请求缺少认证信息");
        }
        boolean vicePresident = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch("ROLE_VICE_PRESIDENT"::equals);
        if (vicePresident) {
            return;
        }
        if (authentication.getPrincipal() instanceof CurrentUser currentUser
                && STRATEGIC_DEPT_ORG_ID.equals(currentUser.getOrgId())) {
            return;
        }
        throw new AuthorizationException("仅战略发展部可调整预警等级");
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "解决预警", description = "确认并解决预警")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AlertResponse>> resolveAlert(
            @PathVariable Long id,
            @Valid @RequestBody ResolveAlertRequest request,
            Authentication authentication
    ) {
        alertAccessService.requireAccessibleAlert(id, authentication);

        Long handledBy = extractUserId(authentication);
        Alert resolvedAlert = alertApplicationService.resolveAlert(
                id, handledBy, request.getResolution()
        );
        return ResponseEntity.ok(ApiResponse.success(AlertResponse.fromEntity(resolvedAlert)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除预警", description = "删除指定预警记录")
    @PreAuthorize("hasAnyRole('STRATEGY_DEPT_HEAD','VICE_PRESIDENT')")
    public ResponseEntity<ApiResponse<Void>> deleteAlert(
            @PathVariable Long id,
            Authentication authentication
    ) {
        alertAccessService.requireAccessibleAlert(id, authentication);
        alertApplicationService.deleteAlert(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ==================== Statistics ====================

    @GetMapping("/count")
    @Operation(summary = "统计预警数量", description = "获取预警总数")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Long>>> countAlerts(Authentication authentication) {
        Map<String, Long> counts = alertAccessService.isAdmin(authentication)
                ? Map.of(
                "total", alertApplicationService.countAlerts(),
                "pending", alertApplicationService.countByStatus(Alert.STATUS_OPEN),
                "triggered", alertApplicationService.countByStatus(Alert.STATUS_IN_PROGRESS),
                "resolved", alertApplicationService.countByStatus(Alert.STATUS_RESOLVED))
                : alertAccessService.countAlertsForCurrentOrg(authentication);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "total", counts.getOrDefault("total", 0L),
                "pending", counts.getOrDefault("pending", 0L),
                "triggered", counts.getOrDefault("triggered", 0L),
                "resolved", counts.getOrDefault("resolved", 0L)
        )));
    }

    @GetMapping("/stats")
    @Operation(summary = "获取预警统计", description = "获取预警统计数据(按严重程度细分)")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AlertStatsDTO>> getAlertStats(Authentication authentication) {
        AlertStatsDTO stats = alertAccessService.isAdmin(authentication)
                ? alertApplicationService.getAlertStats()
                : alertAccessService.buildAlertStatsForCurrentOrg(authentication);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    private Long extractUserId(Authentication authentication) {
        if (authentication == null) {
            throw new AuthorizationException("当前请求缺少认证信息");
        }
        if (authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser.getId();
        }
        String username = authentication.getName();
        try {
            return Long.parseLong(username);
        } catch (NumberFormatException e) {
            throw new AuthorizationException("无法获取当前用户ID，请联系管理员");
        }
    }

    private PageRequest toPageRequest(int page, int size) {
        int normalizedPage = Math.max(page, DEFAULT_PAGE);
        int normalizedSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return PageRequest.of(normalizedPage, normalizedSize);
    }
}
