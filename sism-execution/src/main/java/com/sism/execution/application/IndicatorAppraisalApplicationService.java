package com.sism.execution.application;

import com.sism.exception.ConflictException;
import com.sism.exception.ResourceNotFoundException;
import com.sism.execution.domain.report.PlanReportIndicatorRepository;
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
 * ① 鉴定等级以当前上报轮次的 {@code plan_report_indicator.appraisal_level} 为准；
 * ② 只有「当前审批节点的负责人」可以调整，是审批手段，不是普通编辑；
 * ③ 上级覆盖下级：每个节点都可以改，改了就覆盖，不改则沿用上一节点；
 * ④ 看板/列表仍读 Alert 手动等级，所以每次调整同步写 Alert（尽力而为）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndicatorAppraisalApplicationService {

    private static final String STATUS_IN_REVIEW = "IN_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
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

    /** 行内调整鉴定等级：仅当前审批节点负责人可改 */
    public IndicatorAppraisalContextResponse setLevel(Long indicatorId, String level, Long userId) {
        if (indicatorId == null || indicatorId <= 0) {
            throw new IllegalArgumentException("指标 ID 无效");
        }
        String normalizedLevel = normalizeLevel(level);

        ReportRow row = loadLatestReportRows(List.of(indicatorId)).get(indicatorId);
        if (row == null) {
            throw new ResourceNotFoundException("Report indicator", indicatorId);
        }
        if (!STATUS_IN_REVIEW.equalsIgnoreCase(row.status())) {
            throw new ConflictException("当前指标没有处于审批中的上报，无法调整鉴定等级");
        }

        AppraisalPermission permission = resolvePermission(row, userId);
        if (!permission.canAppraise()) {
            throw new AccessDeniedException("只有当前审批节点的负责人才能调整鉴定等级");
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
     * 优先审批中（IN_REVIEW），其次最近已通过（APPROVED，只读回显），最后任意最新轮次。
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
                       pri.appraisal_level
                FROM public.plan_report_indicator pri
                JOIN public.plan_report pr ON pr.id = pri.report_id
                WHERE pri.indicator_id IN (%s)
                  AND pr.is_deleted = false
                ORDER BY pri.indicator_id ASC, pr.report_month DESC, pr.id DESC
                """.formatted(placeholders);

        Map<Long, ReportRow> inReview = new LinkedHashMap<>();
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
                    normalizeStoredLevel(rs.getString("appraisal_level"))
            );
            latest.putIfAbsent(indicatorId, row);
            if (STATUS_IN_REVIEW.equalsIgnoreCase(row.status())) {
                inReview.putIfAbsent(indicatorId, row);
            } else if (STATUS_APPROVED.equalsIgnoreCase(row.status())) {
                approved.putIfAbsent(indicatorId, row);
            }
        }, indicatorIds.toArray());

        Map<Long, ReportRow> result = new LinkedHashMap<>();
        for (Long indicatorId : indicatorIds) {
            ReportRow row = inReview.get(indicatorId);
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

    private AppraisalPermission resolvePermission(ReportRow row, Long userId) {
        if (row == null) {
            return new AppraisalPermission(false, null, "上报期间才能解锁鉴定进度等级");
        }
        if (!STATUS_IN_REVIEW.equalsIgnoreCase(row.status())) {
            return new AppraisalPermission(false, null, "上报期间才能解锁鉴定进度等级");
        }
        if (row.auditInstanceId() == null) {
            return new AppraisalPermission(false, null, "上报审批尚未启动");
        }

        AuditInstance instance = auditInstanceRepository.findById(row.auditInstanceId()).orElse(null);
        if (instance == null) {
            return new AppraisalPermission(false, null, "审批实例不存在");
        }

        AuditStepInstance pendingStep = instance.resolveCurrentPendingStep().orElse(null);
        if (pendingStep == null) {
            return new AppraisalPermission(false, null, "上报期间才能解锁鉴定进度等级");
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
                    "当前节点只审核，不能修改鉴定等级"
            );
        }

        boolean canApprove = approverResolver.canUserApprove(
                stepDef, userId, instance.getRequesterOrgId(), instance);
        return new AppraisalPermission(
                canApprove,
                stepDef.getStepName(),
                canApprove ? null : "当前审批节点不是你，暂不能调整鉴定进度等级"
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
            throw new IllegalArgumentException("不支持的鉴定等级: " + level);
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
            String appraisalLevel
    ) {
    }

    private record AppraisalPermission(boolean canAppraise, String stepName, String lockHint) {
    }
}
