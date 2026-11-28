package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.IndicatorLevelQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NativeIndicatorLevelQueryRepository implements IndicatorLevelQueryRepository {

    private static final int INDICATOR_LEVEL_COLUMN_COUNT = 5;

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String INDICATOR_STATUS_DISTRIBUTED = "DISTRIBUTED";

    /**
     * 与 NativeAppraisalSummaryQueryRepository 的明细查询同构（pri.report_id = pr.id +
     * pr.is_deleted 过滤），只认 APPROVED 月报；行主体 = 指标 target 组织
     * （责任单位口径），orgId 条件按是否传入动态拼接（避免 typed-null 参数在
     * PostgreSQL 原生 SQL 里报参数类型不确定）。
     */
    private static final String SQL_INDICATOR_LEVELS_BASE = """
            SELECT
                pri.indicator_id,
                i.indicator_desc,
                pr.report_month,
                pri.self_rating,
                pri.appraisal_level
            FROM public.plan_report pr
            INNER JOIN public.plan_report_indicator pri
                    ON pri.report_id = pr.id
            INNER JOIN public.indicator i
                    ON i.id = pri.indicator_id
                   AND COALESCE(i.is_deleted, false) = false
                   AND i.status = :distributedStatus
            WHERE pr.report_month BETWEEN :startMonth AND :endMonth
              AND pr.status = :approvedStatus
              AND pr.is_deleted = false
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<IndicatorLevelRow> fetchIndicatorLevels(
            String startMonth, String endMonth, Long targetOrgId) {
        String sql = SQL_INDICATOR_LEVELS_BASE
                + (targetOrgId == null ? "" : " AND i.target_org_id = :targetOrgId")
                + " ORDER BY pr.report_month ASC, pri.indicator_id ASC";
        var query = entityManager.createNativeQuery(sql)
                .setParameter("startMonth", startMonth)
                .setParameter("endMonth", endMonth)
                .setParameter("approvedStatus", STATUS_APPROVED)
                .setParameter("distributedStatus", INDICATOR_STATUS_DISTRIBUTED);
        if (targetOrgId != null) {
            query.setParameter("targetOrgId", targetOrgId);
        }

        List<Object[]> rows = query.getResultList();
        List<IndicatorLevelRow> result = new java.util.ArrayList<>(rows.size());
        for (Object[] row : rows) {
            if (row == null || row.length != INDICATOR_LEVEL_COLUMN_COUNT) {
                throw new IllegalStateException("Unexpected column count for indicator levels: expected "
                        + INDICATOR_LEVEL_COLUMN_COUNT + " but was " + (row == null ? 0 : row.length));
            }
            result.add(new IndicatorLevelRow(
                    toLong(row[0]),
                    row[1] == null ? null : row[1].toString(),
                    row[2] == null ? null : row[2].toString(),
                    toNullableString(row[3]),
                    toNullableString(row[4])
            ));
        }
        return result;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /** 空串归一为 null（COALESCE(NULLIF(...)) 口径在 SQL 侧已有，这里兜住脏数据） */
    private static String toNullableString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
