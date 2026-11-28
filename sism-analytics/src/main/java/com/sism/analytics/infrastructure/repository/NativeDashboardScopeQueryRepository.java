package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.DashboardScopeQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

@Repository
public class NativeDashboardScopeQueryRepository implements DashboardScopeQueryRepository {

    private static final String ORG_TYPE_ACADEMIC = "academic";

    private static final String SQL_ORG_TYPE = """
            SELECT o.type
            FROM public.sys_org o
            WHERE o.id = :orgId
              AND COALESCE(o.is_deleted, false) = false
            """;

    /**
     * 职能部门「有下发关联」的学院集合（2026-11-27 拍板）：学院线指标的父级链
     * （indicator → sys_task → plan）上 plan.functional_org_id = 本部门 的学院。
     * 不限指标下发状态——线存在即算关联（学院列表可见性由线决定，不看指标状态）。
     */
    private static final String SQL_LINKED_COLLEGE_IDS = """
            SELECT DISTINCT i.target_org_id
            FROM public.indicator i
            INNER JOIN public.sys_task t
                    ON i.task_id = t.task_id
            INNER JOIN public.plan p
                    ON t.plan_id = p.id
            INNER JOIN public.sys_org o
                    ON o.id = i.target_org_id
                   AND CAST(o.type AS varchar) = :academicType
                   AND COALESCE(o.is_deleted, false) = false
            WHERE COALESCE(i.is_deleted, false) = false
              AND i.target_org_id IS NOT NULL
              AND p.functional_org_id = :functionalOrgId
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String findOrgTypeById(Long orgId) {
        if (orgId == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<String> rows = entityManager.createNativeQuery(SQL_ORG_TYPE)
                .setParameter("orgId", orgId)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> findLinkedCollegeIds(Long functionalOrgId) {
        if (functionalOrgId == null) {
            return List.of();
        }
        List<?> rows = entityManager.createNativeQuery(SQL_LINKED_COLLEGE_IDS)
                .setParameter("academicType", ORG_TYPE_ACADEMIC)
                .setParameter("functionalOrgId", functionalOrgId)
                .getResultList();
        return rows.stream()
                .filter(Objects::nonNull)
                .map(value -> {
                    if (value instanceof Number number) {
                        return number.longValue();
                    }
                    return Long.parseLong(value.toString());
                })
                .distinct()
                .toList();
    }
}
