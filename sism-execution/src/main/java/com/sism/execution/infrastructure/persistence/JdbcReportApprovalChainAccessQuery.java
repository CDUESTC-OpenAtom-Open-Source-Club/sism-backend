package com.sism.execution.infrastructure.persistence;

import com.sism.execution.domain.report.ReportApprovalChainAccessQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcReportApprovalChainAccessQuery implements ReportApprovalChainAccessQuery {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean isOrgOnApprovalChain(Long reportId, Long orgId) {
        if (reportId == null || orgId == null) {
            return false;
        }
        Integer hits = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.audit_instance ai
                JOIN public.audit_step_instance asi ON asi.instance_id = ai.id
                WHERE ai.entity_type = 'PLAN_REPORT'
                  AND ai.entity_id = ?
                  AND (ai.requester_org_id = ? OR asi.approver_org_id = ?)
                """,
                Integer.class, reportId, orgId, orgId);
        return hits != null && hits > 0;
    }
}
