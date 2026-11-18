package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * NativeAppraisalSummaryQueryRepository - 已填报上报明细的原生 SQL 实现。
 *
 * <p>表关联与 {@link NativeCollegeLevelQueryRepository} 一致（pri.report_id = pr.id +
 * pr.is_deleted 过滤），额外带出上报组织、指标目标组织与 <b>plan_report.status</b>，
 * 供上层按「上报组织（职能部门）」和「目标组织（责任主体）」两种口径聚合。</p>
 *
 * <p>2026-11-18 用户拍板（按月快照 + 未出结果置灰，废除沿用逻辑）：
 * 不再要求 appraisal_level IS NOT NULL——SUBMITTED/IN_REVIEW 月报的明细行等级通常为 NULL，
 * 但它们是「当月已填报、审批中」的证据（monthState=IN_REVIEW 的数据源）；
 * 鉴定值由上层只从 status=APPROVED 的当月行聚合。状态过滤（APPROVED 计入鉴定值）
 * 的判定放在服务层，便于单测覆盖口径。</p>
 *
 * <p>为什么不在 SQL 里做窗口聚合：一份年度明细最多 12 个月 × 组织数 × 指标数（千级），
 * 拉到内存里按月快照聚合更直观，也避免 generate_series 之类的
 * PostgreSQL 专有语法影响 OpenTenBase 兼容。</p>
 */
@Repository
public class NativeAppraisalSummaryQueryRepository implements AppraisalSummaryQueryRepository {

    private static final int APPRAISAL_ROW_COLUMN_COUNT = 11;

    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_SUBMITTED_LEGACY = "IN_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String INDICATOR_STATUS_DISTRIBUTED = "DISTRIBUTED";

    private static final String SQL_APPRAISAL_ROWS = """
            SELECT
                pr.report_org_id,
                ro.name AS report_org_name,
                pr.report_org_type,
                pr.report_month,
                pri.indicator_id,
                i.indicator_desc,
                i.target_org_id,
                tor.name AS target_org_name,
                pri.appraisal_level,
                i.weight_percent,
                pr.status
            FROM public.plan_report pr
            INNER JOIN public.plan_report_indicator pri
                    ON pri.report_id = pr.id
            INNER JOIN public.indicator i
                    ON i.id = pri.indicator_id
                   AND COALESCE(i.is_deleted, false) = false
                   AND i.status = :distributedStatus
            INNER JOIN public.sys_org ro
                    ON ro.id = pr.report_org_id
                   AND COALESCE(ro.is_deleted, false) = false
            LEFT JOIN public.sys_org tor
                    ON tor.id = i.target_org_id
            WHERE pr.report_month BETWEEN :startMonth AND :endMonth
              AND pr.status IN (:submittedStatus, :submittedLegacyStatus, :approvedStatus)
              AND pr.is_deleted = false
            ORDER BY pr.report_month ASC, pr.report_org_id ASC, pri.indicator_id ASC
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<AppraisalRow> fetchAppraisalRows(String startMonth, String endMonth) {
        List<Object[]> rows = entityManager.createNativeQuery(SQL_APPRAISAL_ROWS)
                .setParameter("startMonth", startMonth)
                .setParameter("endMonth", endMonth)
                .setParameter("submittedStatus", STATUS_SUBMITTED)
                .setParameter("submittedLegacyStatus", STATUS_SUBMITTED_LEGACY)
                .setParameter("approvedStatus", STATUS_APPROVED)
                .setParameter("distributedStatus", INDICATOR_STATUS_DISTRIBUTED)
                .getResultList();

        List<AppraisalRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            if (row == null || row.length != APPRAISAL_ROW_COLUMN_COUNT) {
                throw new IllegalStateException("Unexpected column count for appraisal rows: expected "
                        + APPRAISAL_ROW_COLUMN_COUNT + " but was " + (row == null ? 0 : row.length));
            }
            result.add(new AppraisalRow(
                    toLong(row[0]),
                    row[1] == null ? null : row[1].toString(),
                    row[2] == null ? null : row[2].toString(),
                    row[3] == null ? null : row[3].toString(),
                    toLong(row[4]),
                    row[5] == null ? null : row[5].toString(),
                    row[6] == null ? null : toLong(row[6]),
                    row[7] == null ? null : row[7].toString(),
                    row[8] == null ? null : row[8].toString(),
                    toBigDecimal(row[9]),
                    row[10] == null ? null : row[10].toString()
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

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }
}
