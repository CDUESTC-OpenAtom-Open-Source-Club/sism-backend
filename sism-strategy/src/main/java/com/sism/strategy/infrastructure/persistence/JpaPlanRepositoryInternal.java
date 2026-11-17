package com.sism.strategy.infrastructure.persistence;

import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JpaPlanRepositoryInternal extends JpaRepository<Plan, Long> {
    List<Plan> findByIdIn(List<Long> ids);

    List<Plan> findByTargetOrgId(Long targetOrgId);

    List<Plan> findByCreatedByOrgId(Long createdByOrgId);

    List<Plan> findByCycleId(Long cycleId);

    List<Plan> findByPlanLevel(PlanLevel planLevel);

    java.util.Optional<Plan> findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
            Long cycleId,
            PlanLevel planLevel,
            Long createdByOrgId,
            Long targetOrgId
    );

    @Query("""
            SELECT p
            FROM Plan p
            WHERE p.cycleId = :cycleId
              AND p.planLevel = :planLevel
              AND p.createdByOrgId = :createdByOrgId
              AND p.targetOrgId = :targetOrgId
              AND p.isDeleted = false
            ORDER BY p.createdAt ASC, p.id ASC
            """)
    List<Plan> findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
            @Param("cycleId") Long cycleId,
            @Param("planLevel") PlanLevel planLevel,
            @Param("createdByOrgId") Long createdByOrgId,
            @Param("targetOrgId") Long targetOrgId
    );

    /**
     * 2026-11-17（学院线粒度改造）：按线身份（含归口部门）查存活计划，id 倒序（取最新一条）。
     *
     * <p>归口条件用等值而非 {@code IS NULL OR = ?}：null 语义是「指定归口为 null」，
     * 不匹配任何行——legacy 空壳（functional_org_id 为空）不得被误当成某条线。
     */
    @Query("""
            SELECT p
            FROM Plan p
            WHERE p.cycleId = :cycleId
              AND p.planLevel = :planLevel
              AND p.createdByOrgId = :createdByOrgId
              AND p.targetOrgId = :targetOrgId
              AND p.functionalOrgId = :functionalOrgId
              AND p.isDeleted = false
            ORDER BY p.id DESC
            """)
    List<Plan> findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
            @Param("cycleId") Long cycleId,
            @Param("planLevel") PlanLevel planLevel,
            @Param("createdByOrgId") Long createdByOrgId,
            @Param("targetOrgId") Long targetOrgId,
            @Param("functionalOrgId") Long functionalOrgId
    );

    List<Plan> findByStatusIn(List<String> statuses);

    @Query("""
            SELECT p
            FROM Plan p
            WHERE p.isDeleted = false
              AND (:skipCycleFilter = true OR p.cycleId IN :cycleIds)
            """)
    Page<Plan> findPage(
            @Param("cycleIds") List<Long> cycleIds,
            @Param("skipCycleFilter") boolean skipCycleFilter,
            Pageable pageable
    );

    @Query("""
            SELECT p
            FROM Plan p
            WHERE p.isDeleted = false
              AND p.status IN :statuses
              AND (:skipCycleFilter = true OR p.cycleId IN :cycleIds)
            """)
    Page<Plan> findPageByStatus(
            @Param("cycleIds") List<Long> cycleIds,
            @Param("skipCycleFilter") boolean skipCycleFilter,
            @Param("statuses") List<String> statuses,
            Pageable pageable
    );
}
