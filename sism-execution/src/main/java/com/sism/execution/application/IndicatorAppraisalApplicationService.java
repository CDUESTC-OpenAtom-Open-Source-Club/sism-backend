package com.sism.execution.application;

import com.sism.exception.ConflictException;
import com.sism.exception.ResourceNotFoundException;
import com.sism.execution.domain.report.PlanReportIndicatorRepository;
import com.sism.execution.domain.report.PlanReportStatus;
import com.sism.execution.interfaces.dto.IndicatorAppraisalContextResponse;
import com.sism.shared.domain.indicator.IndicatorAppraisalLevelPort;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 指标鉴定进度（2026-10-28 用户定案）。
 *
 * 业务口径：
 * ① 审核进度以当前上报轮次的 {@code plan_report_indicator.appraisal_level} 为准；
 * ② 只有「当前审批节点的负责人」可以调整，是审批手段，不是普通编辑；
 * ③ 上级覆盖下级：每个节点都可以改，改了就覆盖，不改则沿用上一节点；
 * ④ 看板/列表仍读 Alert 手动等级，所以每次调整同步写 Alert（尽力而为）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndicatorAppraisalApplicationService {

    /*
     * 2026-10-30 修复（P0 鉴定死锁）：上报状态与审批实例状态是两套词表，不能混用。
     *   - plan_report.status：已提交待审批的权威值是 SUBMITTED
     *     （legacy 别名 IN_REVIEW 由 PlanReportStatus 归一化成 SUBMITTED）；
     *   - audit_instance.status：在审状态才是 IN_REVIEW（见 AuditInstance.STATUS_PENDING）。
     * 旧代码拿 "IN_REVIEW" 去比 plan_report.status，恒不成立 →
     * 「谁能评级」判定全部为 false → 可评级节点又强制「全部已评定」→ 结构性死锁。
     * 现在一律：上报侧用 PlanReportStatus，在途侧用审批实例状态。
     */
    private static final Set<String> ALLOWED_LEVELS = Set.of("AHEAD", "NORMAL", "DELAYED");

    /**
     * 2026-10-28 用户定案：只有「上级部门接手后的第一个审批节点」可以评级；
     * 同一部门内部的后续节点（含分管校领导）只审核，不能改等级。
     *
     * 职能部门链：填报人 → 职能部门审批人 → 分管校领导 → 战略发展部（仅第 4 步可评级）
     * 学院链：填报人 → 学院审核人 → 分管职能部门负责人（可评级）→ 分管职能部门校领导 → 战略发展部（可评级）
     */
    private static final Map<String, Set<Integer>> APPRAISAL_ALLOWED_STEP_ORDERS = Map.of(
            "PLAN_APPROVAL_FUNCDEPT", Set.of(4),
            "PLAN_APPROVAL_COLLEGE", Set.of(3, 5)
    );

    private final JdbcTemplate jdbcTemplate;
    private final PlanReportIndicatorRepository planReportIndicatorRepository;
    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final ApproverResolver approverResolver;
    private final List<IndicatorAppraisalLevelPort> appraisalLevelPorts;

    /** 批量查询鉴定上下文（列表页/填报页回显 + 是否可编辑） */
    public List<IndicatorAppraisalContextResponse> getContexts(List<Long> indicatorIds, Long userId) {
        List<Long> ids = normalizeIds(indicatorIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ReportRow> rows = loadLatestReportRows(ids);
        List<IndicatorAppraisalContextResponse> result = new ArrayList<>();
        for (Long indicatorId : ids) {
            ReportRow row = rows.get(indicatorId);
            if (row != null) {
                result.add(toContext(row, userId));
            }
        }
        return result;
    }

    /** 行内调整审核进度：仅当前审批节点负责人可改 */
    public IndicatorAppraisalContextResponse setLevel(Long indicatorId, String level, Long userId) {
        if (indicatorId == null || indicatorId <= 0) {
            throw new IllegalArgumentException("指标 ID 无效");
        }
        String normalizedLevel = normalizeLevel(level);

        ReportRow row = loadLatestReportRows(List.of(indicatorId)).get(indicatorId);
        if (row == null) {
            throw new ResourceNotFoundException("Report indicator", indicatorId);
        }
        // 2026-10-30 修复：前置校验改为「审批实例在途」（上报已提交 + 审批实例 IN_REVIEW），
        // 不再拿 IN_REVIEW 去比 plan_report.status（那是上报侧 SUBMITTED 的 legacy 别名）。
        if (!isApprovalInFlight(row)) {
            throw new ConflictException("当前指标没有处于审批中的上报，无法调整审核进度");
        }

        AppraisalPermission permission = resolvePermission(row, userId);
        if (!permission.canAppraise()) {
            throw new AccessDeniedException("只有当前审批节点的负责人才能调整审核进度");
        }

        planReportIndicatorRepository.updateAppraisalLevel(row.reportId(), indicatorId, normalizedLevel);

        // 看板/列表读 Alert 手动等级：同步过去，失败不回滚审批数据
        appraisalLevelPorts.forEach(port -> {
            try {
                port.syncManualLevel(indicatorId, normalizedLevel, userId);
            } catch (Exception ex) {
                log.warn("Failed to sync appraisal level to alert: indicatorId={}, level={}, error={}",
                        indicatorId, normalizedLevel, ex.getMessage());
            }
        });

        ReportRow updated = loadLatestReportRows(List.of(indicatorId)).get(indicatorId);
        return toContext(updated == null ? row : updated, userId);
    }

    private IndicatorAppraisalContextResponse toContext(ReportRow row, Long userId) {
        AppraisalPermission permission = resolvePermission(row, userId);
        return IndicatorAppraisalContextResponse.builder()
                .indicatorId(row.indicatorId())
                .reportId(row.reportId())
                .reportMonth(row.reportMonth())
                .reportStatus(row.status())
                .appraisalLevel(row.appraisalLevel())
                .canAppraise(permission.canAppraise())
                .currentStepName(permission.stepName())
                .lockHint(permission.lockHint())
                .build();
    }

    /**
     * 取每个指标「当前应鉴定的报告轮次」：
     * 优先审批在途（audit_instance.status = IN_REVIEW），
     * 其次已提交待审（plan_report.status = SUBMITTED，含 legacy IN_REVIEW），
     * 再次最近已通过（APPROVED，只读回显），最后任意最新轮次。
     */
    private Map<Long, ReportRow> loadLatestReportRows(List<Long> indicatorIds) {
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(indicatorIds.size(), "?"));
        String sql = """
                SELECT pri.indicator_id,
                       pr.id AS report_id,
                       pr.report_month,
                       pr.status,
                       pr.audit_instance_id,
                       pri.appraisal_level,
                       ai.status AS instance_status
                FROM public.plan_report_indicator pri
                JOIN public.plan_report pr ON pr.id = pri.report_id
                LEFT JOIN public.audit_instance ai ON ai.id = pr.audit_instance_id
                WHERE pri.indicator_id IN (%s)
                  AND pr.is_deleted = false
                ORDER BY pri.indicator_id ASC, pr.report_month DESC, pr.id DESC
                """.formatted(placeholders);

        Map<Long, ReportRow> inReview = new LinkedHashMap<>();
        Map<Long, ReportRow> submitted = new LinkedHashMap<>();
        Map<Long, ReportRow> approved = new LinkedHashMap<>();
        Map<Long, ReportRow> latest = new LinkedHashMap<>();

        jdbcTemplate.query(sql, rs -> {
            Long indicatorId = rs.getLong("indicator_id");
            ReportRow row = new ReportRow(
                    indicatorId,
                    rs.getLong("report_id"),
                    rs.getString("report_month"),
                    rs.getString("status"),
                    rs.getObject("audit_instance_id") == null ? null : rs.getLong("audit_instance_id"),
                    normalizeStoredLevel(rs.getString("appraisal_level")),
                    rs.getString("instance_status")
            );
            latest.putIfAbsent(indicatorId, row);
            // 权威判据：审批实例在审
            if (isInstanceInReview(row.instanceStatus())) {
                inReview.putIfAbsent(indicatorId, row);
            } else if (isReportStatus(row.status(), PlanReportStatus.SUBMITTED)) {
                submitted.putIfAbsent(indicatorId, row);
            } else if (isReportStatus(row.status(), PlanReportStatus.APPROVED)) {
                approved.putIfAbsent(indicatorId, row);
            }
        }, indicatorIds.toArray());

        Map<Long, ReportRow> result = new LinkedHashMap<>();
        for (Long indicatorId : indicatorIds) {
            ReportRow row = inReview.get(indicatorId);
            if (row == null) {
                row = submitted.get(indicatorId);
            }
            if (row == null) {
                row = approved.get(indicatorId);
            }
            if (row == null) {
                row = latest.get(indicatorId);
            }
            if (row != null) {
                result.put(indicatorId, row);
            }
        }
        return result;
    }

    /**
     * 审批实例是否在审（权威判据）。
     */
    private boolean isInstanceInReview(String instanceStatus) {
        return instanceStatus != null && AuditInstance.STATUS_PENDING.equalsIgnoreCase(instanceStatus.trim());
    }

    /**
     * 上报侧状态判定，复用 {@link PlanReportStatus}（legacy "IN_REVIEW" 会被归一化成 SUBMITTED）。
     */
    private boolean isReportStatus(String rawStatus, PlanReportStatus expected) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return false;
        }
        try {
            return PlanReportStatus.from(rawStatus) == expected;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 审批是否在途：上报已提交（SUBMITTED，含 legacy IN_REVIEW）且审批实例处于 IN_REVIEW。
     */
    private boolean isApprovalInFlight(ReportRow row) {
        if (row == null) {
            return false;
        }
        if (!isReportStatus(row.status(), PlanReportStatus.SUBMITTED)) {
            return false;
        }
        AuditInstance instance = resolveAuditInstance(row);
        return instance != null
                && AuditInstance.STATUS_PENDING.equalsIgnoreCase(String.valueOf(instance.getStatus()));
    }

    private AuditInstance resolveAuditInstance(ReportRow row) {
        if (row == null || row.auditInstanceId() == null) {
            return null;
        }
        return auditInstanceRepository.findById(row.auditInstanceId()).orElse(null);
    }

    /**
     * 2026-10-30 修复：把「在途 / 无实例 / 非本节点」三种情况分开给出可读的 lockHint，
     * 不要一律返回同一句——否则用户看不懂到底卡在哪。
     *
     * <p>权威判据是审批实例状态（{@link AuditInstance#STATUS_PENDING} = IN_REVIEW）；
     * 上报侧状态用 {@link PlanReportStatus} 归一化（SUBMITTED，含 legacy IN_REVIEW）。
     */
    private AppraisalPermission resolvePermission(ReportRow row, Long userId) {
        if (row == null) {
            return new AppraisalPermission(false, null, "未找到该指标的上报记录");
        }
        if (row.auditInstanceId() == null) {
            return new AppraisalPermission(false, null, "上报审批尚未启动，暂不能调整审核进度");
        }

        AuditInstance instance = auditInstanceRepository.findById(row.auditInstanceId()).orElse(null);
        if (instance == null) {
            return new AppraisalPermission(false, null, "上报审批实例不存在，暂不能调整审核进度");
        }
        if (!AuditInstance.STATUS_PENDING.equalsIgnoreCase(String.valueOf(instance.getStatus()))) {
            return new AppraisalPermission(
                    false,
                    null,
                    "本轮上报审批已结束（当前状态：" + instance.getStatus() + "），暂不能调整审核进度"
            );
        }
        if (!isReportStatus(row.status(), PlanReportStatus.SUBMITTED)) {
            return new AppraisalPermission(false, null, "上报尚未提交或已重置，暂不能调整审核进度");
        }

        AuditStepInstance pendingStep = instance.resolveCurrentPendingStep().orElse(null);
        if (pendingStep == null) {
            return new AppraisalPermission(false, null, "审批在途但没有待审节点，暂不能调整审核进度");
        }

        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefById(instance.getFlowDefId());
        AuditStepDef stepDef = flowDef == null || flowDef.getSteps() == null
                ? null
                : flowDef.getSteps().stream()
                        .filter(step -> step.getId() != null && step.getId().equals(pendingStep.getStepDefId()))
                        .findFirst()
                        .orElse(null);
        if (stepDef == null) {
            return new AppraisalPermission(false, null, "审批节点不存在");
        }

        // 2026-10-28：只有上级部门接手后的第一个节点可评级；同部门后续节点只审核
        String flowCode = flowDef.getFlowCode() == null
                ? ""
                : flowDef.getFlowCode().trim().toUpperCase();
        Set<Integer> allowedStepOrders = APPRAISAL_ALLOWED_STEP_ORDERS.getOrDefault(flowCode, Set.of());
        if (stepDef.getStepOrder() == null || !allowedStepOrders.contains(stepDef.getStepOrder())) {
            return new AppraisalPermission(
                    false,
                    stepDef.getStepName(),
                    "当前节点只审核，不能修改审核进度"
            );
        }

        // N-03（2026-10-30）：范围组织由 ApproverResolver 统一解析，不再由调用方传 requesterOrgId
        boolean canApprove = approverResolver.canUserApprove(stepDef, userId, instance);
        return new AppraisalPermission(
                canApprove,
                stepDef.getStepName(),
                canApprove ? null : "当前审批节点不是你，暂不能调整审核进度"
        );
    }

    private List<Long> normalizeIds(List<Long> indicatorIds) {
        if (indicatorIds == null || indicatorIds.isEmpty()) {
            return List.of();
        }
        return indicatorIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .limit(2000)
                .toList();
    }

    private String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String normalized = level.trim().toUpperCase();
        if (!ALLOWED_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("不支持的审核进度: " + level);
        }
        return normalized;
    }

    private String normalizeStoredLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String normalized = level.trim().toUpperCase();
        return ALLOWED_LEVELS.contains(normalized) ? normalized : null;
    }

    private record ReportRow(
            Long indicatorId,
            Long reportId,
            String reportMonth,
            String status,
            Long auditInstanceId,
            String appraisalLevel,
            /** audit_instance.status（审批实例侧的在途判据；无关联实例时为 null） */
            String instanceStatus
    ) {
    }

    private record AppraisalPermission(boolean canAppraise, String stepName, String lockHint) {
    }
}
