package com.sism.execution.infrastructure.persistence;

import com.sism.execution.domain.report.PlanReportIndicatorSnapshot;
import com.sism.execution.domain.report.PlanReportRepository;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ExecutionWorkflowBusinessContextAdapter implements WorkflowBusinessContextPort {

    private static final String PLAN_REPORT_ENTITY_TYPE = "PLAN_REPORT";
    private static final String LEGACY_PLAN_REPORT_ENTITY_TYPE = "PlanReport";

    private final PlanReportRepository planReportRepository;
    private final PlanRepository planRepository;
    private final OrganizationRepository organizationRepository;
    private final com.sism.execution.domain.report.PlanReportIndicatorRepository planReportIndicatorRepository;

    @Override
    public Optional<Long> getPlanIdByEntity(String entityType, Long entityId) {
        if (entityId == null || entityType == null || entityType.isBlank()) {
            return Optional.empty();
        }
        if (!PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType)
                && !LEGACY_PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType)) {
            return Optional.empty();
        }
        return planReportRepository.findById(entityId).map(report -> report.getPlanId());
    }

    /**
     * P1 上报链改造：审批携带审核进度时，投影到该报告全部明细行的
     * appraisal_level（终审/逐级鉴定均覆盖，与节点留痕互为补充）。
     *
     * @deprecated 2026-10-24 用户定案：整体鉴定废弃，改用 {@link #applyAppraisalLevelForIndicators}。
     */
    @Deprecated
    @Override
    public void applyAppraisalLevel(String entityType, Long entityId, String appraisalLevel) {
        if (appraisalLevel == null || appraisalLevel.isBlank()) {
            return;
        }
        if (!isPlanReportEntity(entityType)) {
            return;
        }
        planReportIndicatorRepository.applyAppraisalLevel(entityId, appraisalLevel);
    }

    /**
     * 2026-10-24 用户定案（指标级鉴定）：把逐指标等级投影到对应明细行，
     * 未给出的指标不动。按等级分组，同等级一条 UPDATE。
     */
    @Override
    public void applyAppraisalLevelForIndicators(String entityType, Long entityId,
                                                 java.util.Map<Long, String> indicatorLevels) {
        if (indicatorLevels == null || indicatorLevels.isEmpty()) {
            return;
        }
        if (!isPlanReportEntity(entityType)) {
            return;
        }
        java.util.Map<String, java.util.List<Long>> idsByLevel = new java.util.LinkedHashMap<>();
        indicatorLevels.forEach((indicatorId, level) -> {
            if (indicatorId == null || indicatorId <= 0 || level == null || level.isBlank()) {
                return;
            }
            idsByLevel.computeIfAbsent(level.trim().toUpperCase(), key -> new java.util.ArrayList<>())
                    .add(indicatorId);
        });
        idsByLevel.forEach((level, ids) ->
                planReportIndicatorRepository.applyAppraisalLevelForIndicators(entityId, ids, level));
    }

    private boolean isPlanReportEntity(String entityType) {
        return PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType)
                || LEGACY_PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType);
    }

    /**
     * 2026-10-27 用户定案：上报链末节点通过前校验用——返回报告实例下全部指标 ID。
     */
    @Override
    public java.util.List<Long> findIndicatorIdsByReport(Long entityId) {
        if (entityId == null || entityId <= 0) {
            return java.util.List.of();
        }
        return planReportIndicatorRepository.findByReportId(entityId).stream()
                .map(PlanReportIndicatorSnapshot::indicatorId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 2026-10-28：审批侧读取本报告已评定的逐指标等级，用于逐级覆盖校验。
     */
    @Override
    public java.util.Map<Long, String> findIndicatorAppraisalLevels(Long entityId) {
        if (entityId == null || entityId <= 0) {
            return java.util.Map.of();
        }
        return planReportIndicatorRepository.findAppraisalLevelsByReport(entityId);
    }

    /**
     * 2026-10-30 用户定案：审批拦截文案要报指标名称、不报指标 ID。
     */
    @Override
    public java.util.Map<Long, String> findIndicatorNamesByReport(Long entityId) {
        if (entityId == null || entityId <= 0) {
            return java.util.Map.of();
        }
        return planReportIndicatorRepository.findIndicatorNamesByReport(entityId);
    }

    /**
     * 2026-10-30 用户定案：审批拦截文案要指出去哪里操作——取报告的填报组织名。
     */
    @Override
    public java.util.Optional<String> findReportOrgName(Long entityId) {
        if (entityId == null || entityId <= 0) {
            return java.util.Optional.empty();
        }
        return planReportRepository.findById(entityId)
                .map(report -> resolveOrgName(report.getReportOrgId()));
    }

    @Override
    public Optional<BusinessSummary> getBusinessSummary(String entityType, Long entityId) {
        if (entityId == null || entityType == null || entityType.isBlank()) {
            return Optional.empty();
        }
        if (!PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType)
                && !LEGACY_PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType)) {
            return Optional.empty();
        }
        return planReportRepository.findById(entityId).map(report -> {
            Long sourceOrgId = null;
            String sourceOrgName = null;
            Long targetOrgId = null;
            String targetOrgName = null;
            Long planId = report.getPlanId();
            if (planId != null) {
                planRepository.findById(planId).ifPresent(plan -> {
                });
            }
            var plan = planId == null ? Optional.<com.sism.strategy.domain.plan.Plan>empty() : planRepository.findById(planId);
            if (plan.isPresent()) {
                sourceOrgId = plan.get().getCreatedByOrgId();
                sourceOrgName = resolveOrgName(sourceOrgId);
                targetOrgId = plan.get().getTargetOrgId();
                targetOrgName = resolveOrgName(targetOrgId);
            }
            String reportOrgName = resolveOrgName(report.getReportOrgId());
            String displayName = ((report.getReportMonth() == null ? "" : report.getReportMonth() + " ")
                    + (reportOrgName == null ? "月报" : reportOrgName + "月报")).trim();
            return new BusinessSummary(
                    planId,
                    displayName,
                    sourceOrgId,
                    sourceOrgName,
                    targetOrgId,
                    targetOrgName,
                    displayName
            );
        });
    }

    private String resolveOrgName(Long orgId) {
        if (orgId == null) {
            return null;
        }
        return organizationRepository.findById(orgId)
                .map(org -> org.getName() != null && !org.getName().isBlank() ? org.getName() : "Org#" + orgId)
                .orElse("Org#" + orgId);
    }
}
