package com.sism.analytics.interfaces.rest;

import com.sism.analytics.application.AppraisalSummaryService;
import com.sism.analytics.application.CollegeLevelDashboardService;
import com.sism.analytics.application.DashboardScope;
import com.sism.analytics.application.DashboardScopeResolver;
import com.sism.analytics.application.DashboardSummaryService;
import com.sism.analytics.application.IndicatorLevelService;
import com.sism.analytics.interfaces.dto.AppraisalSummaryResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO;
import com.sism.analytics.interfaces.dto.DashboardSummaryDTO;
import com.sism.analytics.interfaces.dto.DelayedIndicatorsResponseDTO;
import com.sism.analytics.interfaces.dto.DepartmentProgressDTO;
import com.sism.analytics.interfaces.dto.IndicatorLevelsResponseDTO;
import com.sism.common.ApiResponse;
import com.sism.shared.application.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * DashboardSummaryController - 仪表盘汇总API
 * Provides dashboard summary endpoints consumed by the frontend
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "仪表盘汇总", description = "仪表盘汇总和分析接口")
public class DashboardSummaryController {

    private final DashboardSummaryService dashboardSummaryService;
    private final CollegeLevelDashboardService collegeLevelDashboardService;
    private final AppraisalSummaryService appraisalSummaryService;
    private final IndicatorLevelService indicatorLevelService;
    private final DashboardScopeResolver dashboardScopeResolver;

    @GetMapping
    @Operation(summary = "获取仪表盘汇总", description = "获取聚合的仪表盘数据，包括得分、完成率和预警数量；"
            + "2026-11-27 角色分流：学院视角只统计本院、职能部门视角只统计本部门（指标 target=本组织），"
            + "战略部/admin 全量；缓存按视角维度隔离")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DashboardSummaryDTO>> getDashboardSummary(
            @AuthenticationPrincipal CurrentUser currentUser) {
        DashboardSummaryDTO summary =
                dashboardSummaryService.getDashboardSummary(dashboardScopeResolver.resolve(currentUser));
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/department-progress")
    @Operation(summary = "获取部门进度", description = "获取按部门/组织分组的进度数据；"
            + "2026-11-27 角色分流：学院/职能部门视角只返回本组织一行，战略部/admin 返回全部活跃组织")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<DepartmentProgressDTO>>> getDepartmentProgress(
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<DepartmentProgressDTO> progress =
                dashboardSummaryService.getDepartmentProgress(dashboardScopeResolver.resolve(currentUser));
        return ResponseEntity.ok(ApiResponse.success(progress));
    }

    @GetMapping("/recent-activities")
    @Operation(summary = "获取最近活动", description = "获取最近的指标变更和更新；"
            + "2026-11-27 角色分流：学院/职能部门视角只包含指标 target=本组织的活动，战略部/admin 全量")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRecentActivities(
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<Map<String, Object>> activities =
                dashboardSummaryService.getRecentActivities(dashboardScopeResolver.resolve(currentUser));
        return ResponseEntity.ok(ApiResponse.success(activities));
    }

    @GetMapping("/college-levels")
    @Operation(summary = "获取学院指标完成情况", description = "按月聚合各学院鉴定等级计数（AHEAD/NORMAL/DELAYED）；"
            + "2026-11-18 按月快照口径：只有 APPROVED 月报计入鉴定值，"
            + "已填报但审批中（SUBMITTED/IN_REVIEW）的月份等级计数全 0 且 monthState=IN_REVIEW（前端置灰标「审批中」），"
            + "未填报学院 reported=false/monthState=NOT_REPORTED 由前端画灰柱「下发 n 项·未填报」督办；"
            + "等级优先级 appraisal_level > self_rating；"
            + "学院集合 = 当年有指标（下发/未下发）的学院 ∪ 当月已上报学院；"
            + "2026-11-27 角色分流：学院视角只返回本院行，"
            + "职能部门视角只返回「本部门有下发关联」的学院（学院侧存在归口=本部门的线），"
            + "战略部/admin 返回全校学院")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CollegeLevelsResponseDTO>> getCollegeLevels(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        DashboardScope scope = dashboardScopeResolver.resolve(currentUser);
        CollegeLevelsResponseDTO response = collegeLevelDashboardService.getCollegeLevels(year, month, scope);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/college-levels-trend")
    @Operation(summary = "获取学院指标完成情况月度趋势", description = "按年聚合各月份各学院鉴定等级计数；"
            + "months 固定返回 1-12 月全量（升序，逐月独立快照互不沿用），每月含当年有指标/当月已上报的学院"
            + "（未填报学院 reported=false）；口径同 /college-levels（只有 APPROVED 计入鉴定值，审批中置灰）；"
            + "2026-11-27 角色分流：学院视角逐月只返回本院行，"
            + "职能部门视角逐月只返回「本部门有下发关联」的学院")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CollegeLevelTrendResponseDTO>> getCollegeLevelsTrend(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) Integer year) {
        DashboardScope scope = dashboardScopeResolver.resolve(currentUser);
        CollegeLevelTrendResponseDTO response = collegeLevelDashboardService.getCollegeLevelsTrend(year, scope);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/delayed-indicators")
    @Operation(summary = "获取滞后任务清单", description = "按月列出鉴定为 DELAYED 的指标明细（催办用）；"
            + "2026-11-18 拍板：职能部门线（target=职能部门）+ 学院线（target=学院）全量记录，"
            + "orgId/orgName 语义为「责任单位」；按月快照口径只认 APPROVED 月报，"
            + "等级优先级 appraisal_level > self_rating，同一指标多轮已审批上报按首条去重。"
            + "看板「滞后任务响应清单」卡片专用数据源；"
            + "2026-11-27 角色分流：学院/职能部门视角只返回责任单位=本组织的行，"
            + "职能部门视角另含「本部门有下发关联」学院的学院线行")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DelayedIndicatorsResponseDTO>> getDelayedIndicators(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        DashboardScope scope = dashboardScopeResolver.resolve(currentUser);
        DelayedIndicatorsResponseDTO response =
                collegeLevelDashboardService.getDelayedIndicators(year, month, scope);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/appraisal-summary")
    @Operation(summary = "获取审核鉴定口径的看板汇总", description = "一次返回职能部门等级分布（含 1 月至选中月份的逐月快照）、"
            + "滞后任务清单与简报汇总；三处同源于 plan_report_indicator.appraisal_level。"
            + "2026-11-18 按月快照口径（废除沿用逻辑）：每个月的鉴定值只由该月自己的 APPROVED 月报决定，"
            + "各月互不沿用；已填报但审批中的部门鉴定值为 0 且 monthState=IN_REVIEW（前端置灰标「审批中」），"
            + "未填报部门不返回；每部门带当年已下发指标数 dispatchedCount（置灰灰柱高度取该字段）；"
            + "latestMonth 定位本年度最近一个有鉴定数据（APPROVED 且等级非空）的月份；"
            + "2026-11-27 部门行口径：部门行只含部门自己上报的指标（职能部门月报 report_org=该部门），"
            + "不并入归口到该部门的学院线指标，与 indicator-levels 的部门自身口径一致；"
            + "2026-11-27 角色分流：学院视角只统计本院（departments=本院各归口部门），"
            + "职能部门视角只统计本部门（简报/滞后清单含归口到本部门的学院线，departments 只有本部门自身行），"
            + "战略部/admin 全量")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AppraisalSummaryResponseDTO>> getAppraisalSummary(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer limit) {
        DashboardScope scope = dashboardScopeResolver.resolve(currentUser);
        AppraisalSummaryResponseDTO response = appraisalSummaryService.getAppraisalSummary(year, month, limit, scope);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/indicator-levels")
    @Operation(summary = "获取行级鉴定明细", description = "按（责任单位，月份）返回指标级鉴定行"
            + "（指标 id、名称、self_rating、appraisal_level、COALESCE 鉴定优先），"
            + "看板下钻明细面板专用数据源；口径与聚合一致：只有 APPROVED 月报计入，鉴定优先自评。"
            + "month 不传 = 年度模式（返回全年各月）；"
            + "2026-11-27 角色校验：学院/职能部门只能查自己组织，越权 403；战略部/admin 可查任意组织"
            + "（orgId 不传 = 全校）")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<IndicatorLevelsResponseDTO>> getIndicatorLevels(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Long orgId) {
        DashboardScope scope = dashboardScopeResolver.resolve(currentUser);
        IndicatorLevelsResponseDTO response = indicatorLevelService.getIndicatorLevels(year, month, orgId, scope);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新仪表盘汇总缓存", description = "清空 analytics 模块内的仪表盘汇总缓存")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> refreshDashboardCaches() {
        dashboardSummaryService.evictCachedSummaries();
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
