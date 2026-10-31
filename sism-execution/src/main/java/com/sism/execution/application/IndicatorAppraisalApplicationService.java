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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 指标鉴定进度（2026-10-28 用户定案；2026-10-31 评定门禁定稿）。
 *
 * 业务口径：
 * ① 审核进度以当前上报轮次的 {@code plan_report_indicator.appraisal_level} 为准；
 * ② 只有「当前审批节点的负责人」可以调整，是审批手段，不是普通编辑；
 * ③ 上级覆盖下级：每个节点都可以改，改了就覆盖，不改则沿用上一节点；
 * ④ 看板/列表仍读 Alert 手动等级，所以每次调整同步写 Alert（尽力而为）。
 *
 * <p>2026-10-31 定稿：可评定 = 上报审批期间 + 具备任一审批席角色 +
 * 是当前活动节点的候选审批人（{@link ApproverResolver#canUserApprove}，N-03 统一范围组织口径）。
 * 原「步骤白名单」（仅固定 step_order 可评）已删除——同一链上每个在途节点的候选人都能评。
 * 不满足时 lockHint 三分：①非上报期间（保留细分提示）②无席位角色 ③非当前环节评定人（含节点名）。
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
     * 2026-10-31 用户定案：审核进度评定门禁三分口径——
     * 「上报审批期间 + 有评定权限的角色 + 是当前审批节点的评定人」三者全满足才可评定。
     *
     * <p>本集合是「评定权限的角色」判定来源：无任一席位角色 → 原因②；
     * 有席位角色但不是当前节点候选审批人（{@link ApproverResolver#canUserApprove}）→ 原因③。
     * 与 ReportController 的 isAdmin/isSystemAdmin 同源（Spring Security authorities），
     * 由 Controller 层透传，应用服务不再自行反查角色表。
     */
    private static final Set<String> APPRAISAL_SEAT_ROLES = Set.of(
            "ROLE_APPROVER",
            "ROLE_STRATEGY_DEPT_HEAD",
            "ROLE_VICE_PRESIDENT",
            "ROLE_SYSTEM_ADMIN",
            "ROLE_ADMIN"
    );

    private final JdbcTemplate jdbcTemplate;
    private final PlanReportIndicatorRepository planReportIndicatorRepository;
    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final ApproverResolver approverResolver;
    private final List<IndicatorAppraisalLevelPort> appraisalLevelPorts;

    /** 批量查询鉴定上下文（列表页/填报页回显 + 是否可编辑） */
    public List<IndicatorAppraisalContextResponse> getContexts(List<Long> indicatorIds, Long userId) {
        return getContexts(indicatorIds, userId, List.of());
    }

    /**
     * 批量查询鉴定上下文（2026-10-31 定稿：附带当前用户角色码，用于「席位角色」判定）。
     */
    public List<IndicatorAppraisalContextResponse> getContexts(
            List<Long> indicatorIds, Long userId, Collection<String> roleCodes) {
        List<Long> ids = normalizeIds(indicatorIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ReportRow> rows = loadLatestReportRows(ids);
        List<IndicatorAppraisalContextResponse> result = new ArrayList<>();
        for (Long indicatorId : ids) {
            ReportRow row = rows.get(indicatorId);
            if (row != null) {
                result.add(toContext(row, userId, roleCodes));
            }
        }
        return result;
    }

    /** 行内调整审核进度：仅当前审批节点负责人可改 */
    public IndicatorAppraisalContextResponse setLevel(Long indicatorId, String level, Long userId) {
        return setLevel(indicatorId, level, userId, List.of());
    }

    /**
     * 行内调整审核进度（2026-10-31 定稿）：与 canAppraise 同源校验——
     * 上报在途 + 具备审批席角色 + 当前节点候选审批人，任一不满足按对应原因拒绝。
     */
    public IndicatorAppraisalContextResponse setLevel(
            Long indicatorId, String level, Long userId, Collection<String> roleCodes) {
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

        AppraisalPermission permission = resolvePermission(row, userId, roleCodes);
        if (!permission.canAppraise()) {
            // 与 context 端点的 lockHint 同源：前端点击门禁拦住的，这里给同一个原因
            throw new AccessDeniedException(
                    permission.lockHint() != null
                            ? permission.lockHint()
                            : "只有当前审批节点的负责人才能调整审核进度");
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
        return toContext(updated == null ? row : updated, userId, roleCodes);
    }

    private IndicatorAppraisalContextResponse toContext(
            ReportRow row, Long userId, Collection<String> roleCodes) {
        AppraisalPermission permission = resolvePermission(row, userId, roleCodes);
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
     * 2026-10-31 定稿：可评定三条件按 ①②③ 顺序判定，lockHint 三分——
     * <ol>
     *   <li>① 非上报期间：保留既有细分提示（未启动 / 实例不存在 / 已结束 / 已重置）；</li>
     *   <li>② 具备任一审批席角色（{@link #APPRAISAL_SEAT_ROLES}）才继续，否则
     *       「当前角色无评定权限，无法评定审核进度」；</li>
     *   <li>③ 当前节点候选审批人判定（{@link ApproverResolver#canUserApprove}，
     *       N-03 统一范围组织口径）不通过时带节点名拒绝。</li>
     * </ol>
     *
     * <p>原「步骤白名单」（仅固定 step_order 可评）已删除：同一链上每个在途节点的候选人都可评。
     */
    private AppraisalPermission resolvePermission(
            ReportRow row, Long userId, Collection<String> roleCodes) {
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

        // 原因②：无任一审批席角色——在任何节点级判定之前先拦
        if (!hasAnyAppraisalSeatRole(roleCodes)) {
            return new AppraisalPermission(false, null, "当前角色无评定权限，无法评定审核进度");
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

        // 原因③：N-03（2026-10-30）——范围组织由 ApproverResolver 统一解析；
        // 2026-10-31 定稿：当前活动节点的候选审批人即可评（白名单已删）
        boolean canApprove = approverResolver.canUserApprove(stepDef, userId, instance);
        return new AppraisalPermission(
                canApprove,
                stepDef.getStepName(),
                canApprove
                        ? null
                        : "您不是当前审批环节（" + stepDef.getStepName() + "）的评定人，无法评定"
        );
    }

    private boolean hasAnyAppraisalSeatRole(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return false;
        }
        return roleCodes.stream()
                .filter(Objects::nonNull)
                .map(code -> code.trim().toUpperCase())
                .anyMatch(APPRAISAL_SEAT_ROLES::contains);
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
