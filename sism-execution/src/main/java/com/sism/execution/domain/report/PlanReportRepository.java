package com.sism.execution.domain.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * PlanReportRepository - 计划报告仓储接口（领域层）
 * 定义领域层所需的仓储方法
 * 实际 JPA 实现位于 infrastructure.persistence.JpaPlanReportRepository
 */
public interface PlanReportRepository {

    Optional<PlanReport> findById(Long id);

    PlanReport save(PlanReport report);

    List<PlanReport> findByReportOrgId(Long reportOrgId);

    List<PlanReport> findByReportOrgType(ReportOrgType reportOrgType);

    List<PlanReport> findByReportMonth(String reportMonth);

    List<PlanReport> findByStatus(PlanReportStatus status);

    default List<PlanReport> findByStatus(String status) {
        return findByStatus(PlanReportStatus.from(status));
    }

    List<PlanReport> findByReportOrgIdAndStatus(Long reportOrgId, PlanReportStatus status);

    List<PlanReport> findByOrgIdAndMonthRange(Long orgId, String startMonth, String endMonth);

    List<PlanReport> findByStatusAndOrgType(PlanReportStatus status, ReportOrgType orgType);

    List<PlanReport> findAllActive();

    Page<PlanReport> findAllActive(Pageable pageable);

    Page<PlanReport> findByConditions(
            String reportMonth,
            Long reportOrgId,
            ReportOrgType reportOrgType,
            Long planId,
            PlanReportStatus status,
            Pageable pageable);

    default Page<PlanReport> findByConditions(
            String reportMonth,
            Long reportOrgId,
            ReportOrgType reportOrgType,
            Long planId,
            String status,
            Pageable pageable) {
        return findByConditions(reportMonth, reportOrgId, reportOrgType, planId, PlanReportStatus.from(status), pageable);
    }

    Page<PlanReport> findByReportOrgId(Long orgId, Pageable pageable);

    Page<PlanReport> findByStatus(PlanReportStatus status, Pageable pageable);

    default Page<PlanReport> findByStatus(String status, Pageable pageable) {
        return findByStatus(PlanReportStatus.from(status), pageable);
    }

    List<PlanReport> findByPlanId(Long planId);

    long countByStatus(PlanReportStatus status);

    long countByStatusAndReportOrgId(PlanReportStatus status, Long reportOrgId);

    List<PlanReport> findByMonthAndOrgId(String month, Long orgId);

    Optional<PlanReport> findByUniqueKey(Long planId, String reportMonth, ReportOrgType reportOrgType, Long reportOrgId);

    Optional<PlanReport> findLatestByMonthlyScope(Long planId, String reportMonth, ReportOrgType reportOrgType, Long reportOrgId);

    /**
     * 2026-11-13（学院上报按归口部门分批提交）：唯一性从 (plan, report_org, month) 三元组
     * 升级为 (plan, report_org, month, functional_org_id) 四元组。
     * functional_org_id 为 NULL 时保持旧行为兼容：NULL 与 NULL 视为同组、NULL 与非 NULL
     * 不同组（IS NOT DISTINCT FROM 语义）。传 functionalOrgId=null 即查「无归口」分组，
     * 不会复用已按部门落值的草稿。
     */
    Optional<PlanReport> findLatestByMonthlyScope(Long planId, String reportMonth, ReportOrgType reportOrgType,
                                                  Long reportOrgId, Long functionalOrgId);

    /**
     * 2026-11-25（填报导入）：按四元组列出同范围报告，供导入链路自己挑「可复用草稿」。
     *
     * <p>用派生查询而不是上面那条 native SQL：native 查询把 {@code ReportOrgType} 枚举当参数绑定，
     * 在 Hibernate 6 下枚举默认按序数绑定（{@code 0/1}），与库里存的 {@code COLLEGE/FUNC_DEPT}
     * 文本永远不相等——实测导致同一线同一月每导入一次就新建一份草稿。
     * 派生查询走实体元数据（{@code @Enumerated(STRING)}），绑定正确。
     */
    List<PlanReport> findByPlanIdAndReportMonthAndReportOrgTypeAndReportOrgIdAndFunctionalOrgIdAndIsDeletedFalse(
            Long planId, String reportMonth, ReportOrgType reportOrgType, Long reportOrgId, Long functionalOrgId);
}
