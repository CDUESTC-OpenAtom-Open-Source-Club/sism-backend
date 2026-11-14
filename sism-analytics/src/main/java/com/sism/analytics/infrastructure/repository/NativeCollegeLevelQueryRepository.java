package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.CollegeLevelQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * NativeCollegeLevelQueryRepository - 学院指标完成情况聚合的原生 SQL 实现。
 *
 * <p>表关联参照 sism-execution 的 JdbcPlanReportIndicatorRepository /
 * IndicatorAppraisalApplicationService 既有写法（pri.report_id = pr.id + pr.is_deleted 过滤）。</p>
 *
 * <p>口径备注（2026-10-31 核实 schema 后落定）：</p>
 * <ul>
 *   <li>plan_report.status 落库枚举为 DRAFT/SUBMITTED/IN_REVIEW/APPROVED/REJECTED
 *       （V70 约束）；"IN_REVIEW" 是 SUBMITTED 的 legacy 别名（V70 已迁移历史值，但约束仍允许落库），
 *       故 SQL 按「已提交」口径一并计入。</li>
 *   <li>plan_report.report_month 为 'yyyyMM' 六位文本（baseline CHECK ^[0-9]{6}$）。</li>
 *   <li>plan_report_indicator.appraisal_level 为 VARCHAR，CHECK 约束仅允许
 *       NULL / AHEAD / NORMAL / DELAYED（V88）。</li>
 *   <li>sys_org.type 为 PostgreSQL 枚举 org_type（'admin'/'functional'/'academic' 小写）。</li>
 * </ul>
 */
@Repository
public class NativeCollegeLevelQueryRepository implements CollegeLevelQueryRepository {

    private static final int COLLEGE_LEVEL_COLUMN_COUNT = 6;
    private static final int COLLEGE_LEVEL_MONTHLY_COLUMN_COUNT = 7;

    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_SUBMITTED_LEGACY = "IN_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String INDICATOR_STATUS_DISTRIBUTED = "DISTRIBUTED";
    private static final String ORG_TYPE_ACADEMIC = "academic";

    private static final String COMMON_JOINS = """
            FROM public.plan_report pr
            INNER JOIN public.plan_report_indicator pri
                    ON pri.report_id = pr.id
                   AND pri.appraisal_level IS NOT NULL
            INNER JOIN public.indicator i
                    ON i.id = pri.indicator_id
                   AND COALESCE(i.is_deleted, false) = false
                   AND i.status = :distributedStatus
            INNER JOIN public.sys_org o
                    ON o.id = i.target_org_id
                   AND CAST(o.type AS varchar) = :academicType
                   AND COALESCE(o.is_deleted, false) = false
            """;

    private static final String COMMON_FILTERS = """
              AND pr.status IN (:submittedStatus, :submittedLegacyStatus, :approvedStatus)
              AND pr.is_deleted = false
            """;

    private static final String LEVEL_COUNT_COLUMNS = """
                COUNT(CASE WHEN pri.appraisal_level = 'AHEAD' THEN 1 END) AS ahead_count,
                COUNT(CASE WHEN pri.appraisal_level = 'NORMAL' THEN 1 END) AS normal_count,
                COUNT(CASE WHEN pri.appraisal_level = 'DELAYED' THEN 1 END) AS delayed_count,
                COUNT(pri.id) AS total_count
            """;

    private static final String SQL_COLLEGE_LEVEL_COUNTS = """
            SELECT
                o.id AS org_id,
                o.name AS org_name,
            """
            + LEVEL_COUNT_COLUMNS
            + COMMON_JOINS
            + "WHERE pr.report_month = :reportMonth"
            + COMMON_FILTERS + """

            GROUP BY o.id, o.name
            ORDER BY o.name
            """;

    private static final String SQL_COLLEGE_LEVEL_MONTHLY_COUNTS = """
            SELECT
                CAST(SUBSTRING(pr.report_month, 5, 2) AS INT) AS report_mm,
                o.id AS org_id,
                o.name AS org_name,
            """
            + LEVEL_COUNT_COLUMNS
            + COMMON_JOINS
            + "WHERE pr.report_month BETWEEN :startMonth AND :endMonth"
            + COMMON_FILTERS + """

            GROUP BY CAST(SUBSTRING(pr.report_month, 5, 2) AS INT), o.id, o.name
            ORDER BY report_mm ASC, o.name ASC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<CollegeLevelCountRow> fetchCollegeLevelCounts(String reportMonth) {
        List<Object[]> rows = entityManager.createNativeQuery(SQL_COLLEGE_LEVEL_COUNTS)
                .setParameter("reportMonth", reportMonth)
                .setParameter("submittedStatus", STATUS_SUBMITTED)
                .setParameter("submittedLegacyStatus", STATUS_SUBMITTED_LEGACY)
                .setParameter("approvedStatus", STATUS_APPROVED)
                .setParameter("distributedStatus", INDICATOR_STATUS_DISTRIBUTED)
                .setParameter("academicType", ORG_TYPE_ACADEMIC)
                .getResultList();
        List<CollegeLevelCountRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            requireColumnCount(row, COLLEGE_LEVEL_COLUMN_COUNT, "college levels");
            result.add(new CollegeLevelCountRow(
                    toLong(row[0]),
                    row[1] == null ? null : row[1].toString(),
                    toLong(row[2]),
                    toLong(row[3]),
                    toLong(row[4]),
                    toLong(row[5])
            ));
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<CollegeLevelMonthlyCountRow> fetchCollegeLevelMonthlyCounts(String startMonth, String endMonth) {
        List<Object[]> rows = entityManager.createNativeQuery(SQL_COLLEGE_LEVEL_MONTHLY_COUNTS)
                .setParameter("startMonth", startMonth)
                .setParameter("endMonth", endMonth)
                .setParameter("submittedStatus", STATUS_SUBMITTED)
                .setParameter("submittedLegacyStatus", STATUS_SUBMITTED_LEGACY)
                .setParameter("approvedStatus", STATUS_APPROVED)
                .setParameter("distributedStatus", INDICATOR_STATUS_DISTRIBUTED)
                .setParameter("academicType", ORG_TYPE_ACADEMIC)
                .getResultList();
        List<CollegeLevelMonthlyCountRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            requireColumnCount(row, COLLEGE_LEVEL_MONTHLY_COLUMN_COUNT, "college level trend");
            result.add(new CollegeLevelMonthlyCountRow(
                    (int) toLong(row[0]),
                    toLong(row[1]),
                    row[2] == null ? null : row[2].toString(),
                    toLong(row[3]),
                    toLong(row[4]),
                    toLong(row[5]),
                    toLong(row[6])
            ));
        }
        return result;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static void requireColumnCount(Object[] row, int expected, String queryName) {
        if (row == null || row.length != expected) {
            throw new IllegalStateException("Unexpected column count for " + queryName + ": expected "
                    + expected + " but was " + (row == null ? 0 : row.length));
        }
    }
}
