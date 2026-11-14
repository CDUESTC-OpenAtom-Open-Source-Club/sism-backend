package com.sism.execution.infrastructure.persistence;

import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.PlanReportRepository;
import com.sism.execution.domain.report.PlanReportStatus;
import com.sism.execution.domain.report.ReportOrgType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JpaPlanReportRepository - PlanReport 的 JPA 仓储实现
 * 同时继承 JpaRepository 和领域层 PlanReportRepository 接口
 * 位于 infrastructure.persistence 包下，可被 @EnableJpaRepositories 扫描到
 */
@Repository
public interface JpaPlanReportRepository extends JpaRepository<PlanReport, Long>, PlanReportRepository {

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.reportOrgId = :reportOrgId AND pr.isDeleted = false")
    List<PlanReport> findByReportOrgId(@Param("reportOrgId") Long reportOrgId);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.reportOrgType = :reportOrgType AND pr.isDeleted = false")
    List<PlanReport> findByReportOrgType(@Param("reportOrgType") ReportOrgType reportOrgType);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.reportMonth = :reportMonth AND pr.isDeleted = false")
    List<PlanReport> findByReportMonth(@Param("reportMonth") String reportMonth);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.status = :status AND pr.isDeleted = false")
    List<PlanReport> findByStatus(@Param("status") PlanReportStatus status);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.reportOrgId = :reportOrgId AND pr.status = :status AND pr.isDeleted = false")
    List<PlanReport> findByReportOrgIdAndStatus(@Param("reportOrgId") Long reportOrgId,
                                                @Param("status") PlanReportStatus status);

    @Override
    @Query(value = """
            SELECT *
            FROM plan_report pr
            WHERE pr.report_org_id = :orgId
              AND pr.is_deleted = false
              AND to_date(pr.report_month, 'YYYYMM') BETWEEN to_date(:startMonth, 'YYYYMM') AND to_date(:endMonth, 'YYYYMM')
            """, nativeQuery = true)
    List<PlanReport> findByOrgIdAndMonthRange(
            @Param("orgId") Long orgId,
            @Param("startMonth") String startMonth,
            @Param("endMonth") String endMonth);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.status = :status AND pr.reportOrgType = :orgType AND pr.isDeleted = false")
    List<PlanReport> findByStatusAndOrgType(
            @Param("status") PlanReportStatus status,
            @Param("orgType") ReportOrgType orgType);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.isDeleted = false")
    List<PlanReport> findAllActive();

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.isDeleted = false")
    Page<PlanReport> findAllActive(Pageable pageable);

    @Override
    @Query(value = """
            SELECT * FROM plan_report pr
            WHERE (CAST(:reportMonth AS text) IS NULL OR pr.report_month = CAST(:reportMonth AS text))
              AND (CAST(:reportOrgId AS bigint) IS NULL OR pr.report_org_id = CAST(:reportOrgId AS bigint))
              AND (CAST(:reportOrgType AS text) IS NULL OR pr.report_org_type = CAST(:reportOrgType AS text))
              AND (CAST(:planId AS bigint) IS NULL OR pr.plan_id = CAST(:planId AS bigint))
              AND (CAST(:status AS text) IS NULL OR pr.status = CAST(:status AS text))
              AND pr.is_deleted = false
            ORDER BY pr.created_at DESC
            """, nativeQuery = true)
    Page<PlanReport> findByConditions(
            @Param("reportMonth") String reportMonth,
            @Param("reportOrgId") Long reportOrgId,
            @Param("reportOrgType") ReportOrgType reportOrgType,
            @Param("planId") Long planId,
            @Param("status") PlanReportStatus status,
            Pageable pageable);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.reportOrgId = :orgId AND pr.isDeleted = false")
    Page<PlanReport> findByReportOrgId(@Param("orgId") Long orgId, Pageable pageable);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.status = :status AND pr.isDeleted = false")
    Page<PlanReport> findByStatus(@Param("status") PlanReportStatus status, Pageable pageable);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.planId = :planId AND pr.isDeleted = false")
    List<PlanReport> findByPlanId(@Param("planId") Long planId);

    @Override
    @Query("SELECT COUNT(pr) FROM PlanReport pr WHERE pr.status = :status AND pr.isDeleted = false")
    long countByStatus(@Param("status") PlanReportStatus status);

    @Query("SELECT COUNT(pr) FROM PlanReport pr WHERE pr.status = :status AND pr.reportOrgId = :reportOrgId AND pr.isDeleted = false")
    long countByStatusAndReportOrgId(@Param("status") PlanReportStatus status,
                                     @Param("reportOrgId") Long reportOrgId);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.reportMonth = :month AND pr.reportOrgId = :orgId AND pr.isDeleted = false")
    List<PlanReport> findByMonthAndOrgId(
            @Param("month") String month,
            @Param("orgId") Long orgId);

    @Override
    @Query("SELECT pr FROM PlanReport pr WHERE pr.planId = :planId AND pr.reportMonth = :reportMonth AND pr.reportOrgType = :reportOrgType AND pr.reportOrgId = :reportOrgId AND pr.isDeleted = false")
    Optional<PlanReport> findByUniqueKey(
            @Param("planId") Long planId,
            @Param("reportMonth") String reportMonth,
            @Param("reportOrgType") ReportOrgType reportOrgType,
            @Param("reportOrgId") Long reportOrgId);

    @Query("""
            SELECT pr
            FROM PlanReport pr
            WHERE pr.planId = :planId
              AND pr.reportMonth = :reportMonth
              AND pr.reportOrgType = :reportOrgType
              AND pr.reportOrgId = :reportOrgId
              AND pr.isDeleted = false
            ORDER BY pr.submittedAt DESC, pr.id DESC
            """)
    List<PlanReport> findLatestCandidatesByMonthlyScope(
            @Param("planId") Long planId,
            @Param("reportMonth") String reportMonth,
            @Param("reportOrgType") ReportOrgType reportOrgType,
            @Param("reportOrgId") Long reportOrgId);

    @Override
    default Optional<PlanReport> findLatestByMonthlyScope(
            Long planId,
            String reportMonth,
            ReportOrgType reportOrgType,
            Long reportOrgId
    ) {
        return findLatestCandidatesByMonthlyScope(planId, reportMonth, reportOrgType, reportOrgId)
                .stream()
                .findFirst();
    }

    /**
     * 2026-11-13（学院上报按归口部门分批提交）：四元组查找。
     * functional_org_id 用 IS NOT DISTINCT FROM 做 NULL 安全等值（NULL 与 NULL 同组、
     * NULL 与非 NULL 不同组），PostgreSQL / H2（PostgreSQL mode）均原生支持。
     */
    @Override
    @Query(value = """
            SELECT * FROM plan_report pr
            WHERE pr.plan_id = CAST(:planId AS bigint)
              AND pr.report_month = CAST(:reportMonth AS text)
              AND pr.report_org_type = CAST(:reportOrgType AS text)
              AND pr.report_org_id = CAST(:reportOrgId AS bigint)
              AND pr.functional_org_id IS NOT DISTINCT FROM CAST(:functionalOrgId AS bigint)
              AND pr.is_deleted = false
            ORDER BY pr.submitted_at DESC, pr.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<PlanReport> findLatestByMonthlyScope(
            @Param("planId") Long planId,
            @Param("reportMonth") String reportMonth,
            @Param("reportOrgType") ReportOrgType reportOrgType,
            @Param("reportOrgId") Long reportOrgId,
            @Param("functionalOrgId") Long functionalOrgId);
}
