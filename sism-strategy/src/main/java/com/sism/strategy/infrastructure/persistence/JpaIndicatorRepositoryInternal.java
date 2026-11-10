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
            ORDER BY COALESCE(i.sort_order, 2147483647) ASC, i.id ASC
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
     *
     * <p>2026-11-10 用户拍板口径（归口可见性放行）：战略部直发的学院子指标
     * （owner=战略发展部、target=二级学院、parent_indicator_id 非空），
     * 其<b>归口职能部门</b>（即父级核心指标的目标组织）亦可见，
     * 用于审核进度鉴定与归口查看。原两分支口径下该类子指标对归口部门完全不可见，
     * 导致上报审批环节要求的「归口部门完成子指标审核进度鉴定」不可达（上报链死锁）。
     *
     * <p>本次放行<b>不区分指标状态</b>：DRAFT 子指标也会出现在归口视角，
     * 供鉴定提前准备；写权限不受影响（写由 StrategyOrgWriteGuard/isDispatchOwned 闸门另行管）。
     */
    @Query(value = """
            SELECT i.* FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            INNER JOIN cycle c ON p.cycle_id = c.id
            WHERE c.year = :year
              AND COALESCE(i.is_deleted, false) = false
              AND (i.target_org_id = :orgId OR i.owner_org_id = :orgId
                   OR (
                     i.parent_indicator_id IS NOT NULL
                     AND EXISTS (
                       SELECT 1 FROM public.indicator p2
                       WHERE p2.id = i.parent_indicator_id
                         AND p2.target_org_id = :orgId
                         AND COALESCE(p2.is_deleted, false) = false
                     )
                   ))
            ORDER BY COALESCE(i.sort_order, 2147483647) ASC, i.id ASC
            """,
           countQuery = """
            SELECT COUNT(*) FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            INNER JOIN cycle c ON p.cycle_id = c.id
            WHERE c.year = :year
              AND COALESCE(i.is_deleted, false) = false
              AND (i.target_org_id = :orgId OR i.owner_org_id = :orgId
                   OR (
                     i.parent_indicator_id IS NOT NULL
                     AND EXISTS (
                       SELECT 1 FROM public.indicator p2
                       WHERE p2.id = i.parent_indicator_id
                         AND p2.target_org_id = :orgId
                         AND COALESCE(p2.is_deleted, false) = false
                     )
                   ))
            """,
           nativeQuery = true)
    Page<Indicator> findByYearAndVisibleOrg(
            @Param("year") Integer year,
            @Param("orgId") Long orgId,
            Pageable pageable);

    /**
     * 2026-11-10 归口可见性放行（与 {@link #findByYearAndVisibleOrg} 同口径，无年份过滤版）：
     * 在「目标组织 = 本部门」或「拥有组织 = 本部门」之外新增第三分支——
     * 其父级核心指标的目标组织 = 本部门（归口职能部门）的子指标可见。
     * 详见 findByYearAndVisibleOrg 的口径注释。
     */
    @Query(value = """
            SELECT i.* FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            WHERE COALESCE(i.is_deleted, false) = false
              AND (i.target_org_id = :orgId OR i.owner_org_id = :orgId
                   OR (
                     i.parent_indicator_id IS NOT NULL
                     AND EXISTS (
                       SELECT 1 FROM public.indicator p2
                       WHERE p2.id = i.parent_indicator_id
                         AND p2.target_org_id = :orgId
                         AND COALESCE(p2.is_deleted, false) = false
                     )
                   ))
            ORDER BY COALESCE(i.sort_order, 2147483647) ASC, i.id ASC
            """,
           countQuery = """
            SELECT COUNT(*) FROM indicator i
            INNER JOIN sys_task t ON i.task_id = t.task_id
            INNER JOIN plan p ON t.plan_id = p.id
            WHERE COALESCE(i.is_deleted, false) = false
              AND (i.target_org_id = :orgId OR i.owner_org_id = :orgId
                   OR (
                     i.parent_indicator_id IS NOT NULL
                     AND EXISTS (
                       SELECT 1 FROM public.indicator p2
                       WHERE p2.id = i.parent_indicator_id
                         AND p2.target_org_id = :orgId
                         AND COALESCE(p2.is_deleted, false) = false
                     )
                   ))
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

    /**
     * 2026-11-05（学院指标直发改造）：学院下发导入的「父级核心指标」锚点。
     *
     * <p>口径：归属 = 战略发展部 + 目标组织是职能部门（sys_org.type='functional'）+ 自身无父级 +
     * 未删除 + <b>同一考核周期</b>（经 sys_task → plan → cycle_id 关联）。
     * 指标表本身不带 cycle，必须经任务与计划关联才能按周期收敛——否则历年同名核心指标会互相串号。
     */
    @Query(value = """
            SELECT i.* FROM public.indicator i
            INNER JOIN public.sys_task t ON i.task_id = t.task_id
            INNER JOIN public.plan p ON t.plan_id = p.id
            INNER JOIN public.sys_org o ON o.id = i.target_org_id
            WHERE i.owner_org_id = :ownerOrgId
              AND i.parent_indicator_id IS NULL
              AND COALESCE(i.is_deleted, false) = false
              AND COALESCE(t.is_deleted, false) = false
              AND COALESCE(p.is_deleted, false) = false
              AND p.cycle_id = :cycleId
              AND o.type = 'functional'
              AND COALESCE(o.is_deleted, false) = false
            ORDER BY i.id
            """, nativeQuery = true)
    List<Indicator> findCoreDispatchAnchors(@Param("ownerOrgId") Long ownerOrgId,
                                             @Param("cycleId") Long cycleId);

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
