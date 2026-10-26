package com.sism.strategy.infrastructure.persistence;

import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.organization.domain.OrgType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface JpaIndicatorRepositoryInternal extends JpaRepository<Indicator, Long> {
    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    Optional<Indicator> findByIdAndIsDeletedFalse(Long id);

    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    Optional<Indicator> findByIdAndOwnerOrgIdAndIsDeletedFalse(Long id, Long ownerOrgId);

    boolean existsByIdAndIsDeletedFalse(Long id);

    List<Indicator> findAllByIsDeletedFalse();

    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    Page<Indicator> findAllByIsDeletedFalse(Pageable pageable);

    List<Indicator> findByOwnerOrgIdAndIsDeletedFalse(Long ownerOrgId);
    List<Indicator> findByTargetOrgIdAndIsDeletedFalse(Long targetOrgId);
    List<Indicator> findByStatusAndIsDeletedFalse(IndicatorStatus status);

    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    Page<Indicator> findByStatusAndIsDeletedFalse(IndicatorStatus status, Pageable pageable);
    List<Indicator> findByParentIndicatorIdAndIsDeletedFalse(Long parentIndicatorId);

    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    List<Indicator> findByTaskIdAndIsDeletedFalse(Long taskId);

    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    @Query("""
            SELECT i FROM Indicator i
            WHERE i.taskId IN :taskIds
              AND i.isDeleted = false
            """)
    List<Indicator> findByTaskIds(@Param("taskIds") List<Long> taskIds);

    /**
     * 根据任务ID列表分页查询指标
     */
    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    @Query("""
            SELECT i FROM Indicator i
            WHERE i.taskId IN :taskIds
              AND i.isDeleted = false
            """)
    Page<Indicator> findByTaskIds(@Param("taskIds") List<Long> taskIds, Pageable pageable);

    /**
     * 根据任务ID列表统计指标数量
     */
    @Query("""
            SELECT COUNT(i) FROM Indicator i
            WHERE i.taskId IN :taskIds
              AND i.isDeleted = false
            """)
    long countByTaskIds(@Param("taskIds") List<Long> taskIds);

    /**
     * 根据年份获取指标（通过 Cycle -> Plan -> Task -> Indicator 关系链）
     * 使用原生 SQL，直接 JOIN 获取正确的指标
     *
     * 注意：此查询返回的 Indicator 对象中 ownerOrg 和 targetOrg 是懒加载的，
     * 需要在调用方使用 Hibernate.initialize() 或批量查询初始化这些关联。
     */
    @Query(value = """
            SELECT i.* FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            INNER JOIN cycle c ON p.cycle_id = c.id
            WHERE c.year = :year
              AND COALESCE(i.is_deleted, false) = false
            ORDER BY i.id DESC
            """,
           countQuery = """
            SELECT COUNT(*) FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            INNER JOIN cycle c ON p.cycle_id = c.id
            WHERE c.year = :year
              AND COALESCE(i.is_deleted, false) = false
            """,
           nativeQuery = true)
    Page<Indicator> findByYear(@Param("year") Integer year, Pageable pageable);

    /**
     * 2026-10-26 数据隔离修复（审批链验证报告 R13）：按可见组织过滤指标——
     * 部门账号只能看到「目标组织 = 本部门」或「拥有组织 = 本部门」的指标。
     */
    @Query(value = """
            SELECT i.* FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            INNER JOIN cycle c ON p.cycle_id = c.id
            WHERE c.year = :year
              AND COALESCE(i.is_deleted, false) = false
              AND (i.target_org_id = :orgId OR i.owner_org_id = :orgId)
            ORDER BY i.id DESC
            """,
           countQuery = """
            SELECT COUNT(*) FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            INNER JOIN cycle c ON p.cycle_id = c.id
            WHERE c.year = :year
              AND COALESCE(i.is_deleted, false) = false
              AND (i.target_org_id = :orgId OR i.owner_org_id = :orgId)
            """,
           nativeQuery = true)
    Page<Indicator> findByYearAndVisibleOrg(
            @Param("year") Integer year,
            @Param("orgId") Long orgId,
            Pageable pageable);

    @Query(value = """
            SELECT i.* FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            WHERE COALESCE(i.is_deleted, false) = false
              AND (i.target_org_id = :orgId OR i.owner_org_id = :orgId)
            ORDER BY i.id DESC
            """,
           countQuery = """
            SELECT COUNT(*) FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            WHERE COALESCE(i.is_deleted, false) = false
              AND (i.target_org_id = :orgId OR i.owner_org_id = :orgId)
            """,
           nativeQuery = true)
    Page<Indicator> findByVisibleOrg(@Param("orgId") Long orgId, Pageable pageable);

    /**
     * 根据拥有组织ID和目标组织ID查询指标
     * 用于批量撤回操作
     */
    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    List<Indicator> findByOwnerOrgIdAndTargetOrgIdAndIsDeletedFalse(Long ownerOrgId, Long targetOrgId);

    List<Indicator> findByIdInAndIsDeletedFalse(List<Long> ids);

    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    @Query("""
            SELECT i FROM Indicator i
            WHERE i.isDeleted = false
              AND i.ownerOrg.type <> :functionalType
            """)
    List<Indicator> findFirstLevelIndicators(@Param("functionalType") OrgType functionalType);

    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    @Query("""
            SELECT i FROM Indicator i
            WHERE i.isDeleted = false
              AND i.ownerOrg.type = :functionalType
            """)
    List<Indicator> findSecondLevelIndicators(@Param("functionalType") OrgType functionalType);

    @EntityGraph(attributePaths = {"ownerOrg", "targetOrg"})
    List<Indicator> findByIndicatorDescContainingIgnoreCaseAndIsDeletedFalse(String keyword);
}
