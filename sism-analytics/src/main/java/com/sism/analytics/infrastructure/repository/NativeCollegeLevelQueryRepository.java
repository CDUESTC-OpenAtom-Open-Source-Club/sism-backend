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
 * IndicatorAppraisalApplicationService 既有写法（pri.report_id = pr.id + pr.is_deleted 过滤）；
 * 按年过滤走 indicator → sys_task → plan → cycle.year 关系链（与指标列表 findByYear 同口径）。</p>
 *
 * <p>口径备注（2026-11-17 拍板升级，基础事实 2026-10-31 核实）：</p>
 * <ul>
 *   <li>plan_report.status 落库枚举为 DRAFT/SUBMITTED/IN_REVIEW/APPROVED/REJECTED
 *       （V70 约束）；"IN_REVIEW" 是 SUBMITTED 的 legacy 别名（V70 已迁移历史值，但约束仍允许落库），
 *       故 SQL 按「已提交」口径一并计入。</li>
 *   <li>plan_report.report_month 为 'yyyyMM' 六位文本（baseline CHECK ^[0-9]{6}$）。</li>
 *   <li>plan_report_indicator.appraisal_level / self_rating 为 VARCHAR，CHECK 约束均仅允许
 *       NULL / AHEAD / NORMAL / DELAYED（V88）。等级取值优先级 appraisal_level &gt; self_rating：
 *       COALESCE(NULLIF(appraisal_level,''), NULLIF(self_rating,''))。</li>
 *   <li>sys_org.type 为 PostgreSQL 枚举 org_type（'admin'/'functional'/'academic' 小写）。</li>
 *   <li>indicator.status ∈ DRAFT/PENDING/DISTRIBUTED（V34/V35 约束）；
 *       DRAFT/PENDING 视为「未下发」，DISTRIBUTED 视为「已下发」。</li>
 * </ul>
 */
@Repository
public class NativeCollegeLevelQueryRepository implements CollegeLevelQueryRepository {

    private static final int COLLEGE_LEVEL_COLUMN_COUNT = 6;
    private static final int COLLEGE_LEVEL_MONTHLY_COLUMN_COUNT = 7;
    private static final int DISPATCH_COUNT_COLUMN_COUNT = 4;
    private static final int REPORTED_MONTH_COLUMN_COUNT = 3;
    private static final int DELAYED_INDICATOR_COLUMN_COUNT = 7;

    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_SUBMITTED_LEGACY = "IN_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String INDICATOR_STATUS_DISTRIBUTED = "DISTRIBUTED";
    private static final String ORG_TYPE_ACADEMIC = "academic";
    private static final String REPORT_ORG_TYPE_COLLEGE = "COLLEGE";

    /**
     * 等级取值表达式：上级鉴定优先，自评兜底（2026-11-17 拍板 appraisal_level &gt; self_rating）。
     */
    private static final String EFFECTIVE_LEVEL =
            "COALESCE(NULLIF(pri.appraisal_level, ''), NULLIF(pri.self_rating, ''))";

    private static final String COMMON_JOINS = """
            FROM public.plan_report pr
            INNER JOIN public.plan_report_indicator pri
                    ON pri.report_id = pr.id
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

    /**
     * 有效等级仅在 ahead/normal/delayed 明细上计数：
     * WHERE 过滤掉两级等级均为 NULL 的行后 COUNT(pri.id) 即可计数合计。
     * 注意拼接点不能用文本块行尾（Java 会剥离行尾空格），统一用普通字符串逐行拼。
     */
    private static final String LEVEL_COUNT_COLUMNS =
            "                COUNT(CASE WHEN " + EFFECTIVE_LEVEL + " = 'AHEAD' THEN 1 END) AS ahead_count,\n"
            + "                COUNT(CASE WHEN " + EFFECTIVE_LEVEL + " = 'NORMAL' THEN 1 END) AS normal_count,\n"
            + "                COUNT(CASE WHEN " + EFFECTIVE_LEVEL + " = 'DELAYED' THEN 1 END) AS delayed_count,\n"
            + "                COUNT(pri.id) AS total_count\n";

    private static final String SQL_COLLEGE_LEVEL_COUNTS =
            "SELECT\n"
            + "                o.id AS org_id,\n"
            + "                o.name AS org_name,\n"
            + LEVEL_COUNT_COLUMNS
            + COMMON_JOINS
            + "WHERE pr.report_month = :reportMonth\n"
            + "              AND " + EFFECTIVE_LEVEL + " IS NOT NULL"
            + COMMON_FILTERS + """

            GROUP BY o.id, o.name
            ORDER BY o.name
            """;

    private static final String SQL_COLLEGE_LEVEL_MONTHLY_COUNTS =
            "SELECT\n"
            + "                CAST(SUBSTRING(pr.report_month, 5, 2) AS INT) AS report_mm,\n"
            + "                o.id AS org_id,\n"
            + "                o.name AS org_name,\n"
            + LEVEL_COUNT_COLUMNS
            + COMMON_JOINS
            + "WHERE pr.report_month BETWEEN :startMonth AND :endMonth\n"
            + "              AND " + EFFECTIVE_LEVEL + " IS NOT NULL"
            + COMMON_FILTERS + """

            GROUP BY CAST(SUBSTRING(pr.report_month, 5, 2) AS INT), o.id, o.name
            ORDER BY report_mm ASC, o.name ASC
            """;

    private static final String SQL_COLLEGE_DISPATCH_COUNTS = """
            SELECT
                o.id AS org_id,
                o.name AS org_name,
                SUM(CASE WHEN i.status = 'DISTRIBUTED' THEN 1 ELSE 0 END) AS dispatched_count,
                SUM(CASE WHEN i.status IN ('DRAFT', 'PENDING') THEN 1 ELSE 0 END) AS undispatched_count
            FROM public.indicator i
            INNER JOIN public.sys_task t
                    ON i.task_id = t.task_id
            INNER JOIN public.plan p
                    ON t.plan_id = p.id
            INNER JOIN public.cycle c
                    ON p.cycle_id = c.id
            INNER JOIN public.sys_org o
                    ON o.id = i.target_org_id
                   AND CAST(o.type AS varchar) = :academicType
                   AND COALESCE(o.is_deleted, false) = false
            WHERE COALESCE(i.is_deleted, false) = false
              AND c."year" = :year
            GROUP BY o.id, o.name
            ORDER BY o.name
            """;

    private static final String SQL_COLLEGE_REPORTED_MONTHS = """
            SELECT
                CAST(SUBSTRING(pr.report_month, 5, 2) AS INT) AS report_mm,
                pr.report_org_id AS org_id,
                o.name AS org_name
            FROM public.plan_report pr
            INNER JOIN public.sys_org o
                    ON o.id = pr.report_org_id
            WHERE pr.report_month BETWEEN :startMonth AND :endMonth
              AND pr.report_org_type = :collegeType
              AND pr.status IN (:submittedStatus, :submittedLegacyStatus, :approvedStatus)
              AND pr.is_deleted = false
            GROUP BY CAST(SUBSTRING(pr.report_month, 5, 2) AS INT), pr.report_org_id, o.name
            ORDER BY report_mm ASC, org_name ASC
            """;

    private static final String SQL_DELAYED_INDICATORS = """
            SELECT
                pri.indicator_id AS indicator_id,
                i.indicator_desc AS indicator_name,
                o.id AS org_id,
                o.name AS org_name,
                owner.name AS owner_org_name,
                pri.self_rating AS self_rating,
                pri.appraisal_level AS appraisal_level
            FROM public.plan_report pr
            INNER JOIN public.plan_report_indicator pri
                    ON pri.report_id = pr.id
            INNER JOIN public.indicator i
                    ON i.id = pri.indicator_id
                   AND COALESCE(i.is_deleted, false) = false
                   AND i.status = :distributedStatus
            INNER JOIN public.sys_org o
                    ON o.id = i.target_org_id
                   AND CAST(o.type AS varchar) = :academicType
                   AND COALESCE(o.is_deleted, false) = false
            LEFT JOIN public.sys_org owner
                    ON owner.id = i.owner_org_id
            WHERE pr.report_month = :reportMonth
            """
            + "              AND " + EFFECTIVE_LEVEL + " = 'DELAYED'\n"
            + COMMON_FILTERS + """

            ORDER BY o.name ASC, i.id ASC
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
                    toStringOrNull(row[1]),
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
                    toStringOrNull(row[2]),
                    toLong(row[3]),
                    toLong(row[4]),
                    toLong(row[5]),
                    toLong(row[6])
            ));
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<CollegeDispatchCountRow> fetchCollegeDispatchCounts(Integer year) {
        List<Object[]> rows = entityManager.createNativeQuery(SQL_COLLEGE_DISPATCH_COUNTS)
                .setParameter("year", year)
                .setParameter("academicType", ORG_TYPE_ACADEMIC)
                .getResultList();
        List<CollegeDispatchCountRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            requireColumnCount(row, DISPATCH_COUNT_COLUMN_COUNT, "college dispatch counts");
            result.add(new CollegeDispatchCountRow(
                    toLong(row[0]),
                    toStringOrNull(row[1]),
                    toLong(row[2]),
                    toLong(row[3])
            ));
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<CollegeReportedMonthRow> fetchCollegeReportedMonths(String startMonth, String endMonth) {
        List<Object[]> rows = entityManager.createNativeQuery(SQL_COLLEGE_REPORTED_MONTHS)
                .setParameter("startMonth", startMonth)
                .setParameter("endMonth", endMonth)
                .setParameter("collegeType", REPORT_ORG_TYPE_COLLEGE)
                .setParameter("submittedStatus", STATUS_SUBMITTED)
                .setParameter("submittedLegacyStatus", STATUS_SUBMITTED_LEGACY)
                .setParameter("approvedStatus", STATUS_APPROVED)
                .getResultList();
        List<CollegeReportedMonthRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            requireColumnCount(row, REPORTED_MONTH_COLUMN_COUNT, "college reported months");
            result.add(new CollegeReportedMonthRow(
                    (int) toLong(row[0]),
                    toLong(row[1]),
                    toStringOrNull(row[2])
            ));
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DelayedIndicatorRow> fetchDelayedIndicators(String reportMonth) {
        List<Object[]> rows = entityManager.createNativeQuery(SQL_DELAYED_INDICATORS)
                .setParameter("reportMonth", reportMonth)
                .setParameter("submittedStatus", STATUS_SUBMITTED)
                .setParameter("submittedLegacyStatus", STATUS_SUBMITTED_LEGACY)
                .setParameter("approvedStatus", STATUS_APPROVED)
                .setParameter("distributedStatus", INDICATOR_STATUS_DISTRIBUTED)
                .setParameter("academicType", ORG_TYPE_ACADEMIC)
                .getResultList();
        List<DelayedIndicatorRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            requireColumnCount(row, DELAYED_INDICATOR_COLUMN_COUNT, "delayed indicators");
            result.add(new DelayedIndicatorRow(
                    toLong(row[0]),
                    toStringOrNull(row[1]),
                    toLong(row[2]),
                    toStringOrNull(row[3]),
                    toStringOrNull(row[4]),
                    toStringOrNull(row[5]),
                    toStringOrNull(row[6])
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

    private static String toStringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static void requireColumnCount(Object[] row, int expected, String queryName) {
        if (row == null || row.length != expected) {
            throw new IllegalStateException("Unexpected column count for " + queryName + ": expected "
                    + expected + " but was " + (row == null ? 0 : row.length));
        }
    }
}
