package com.sism.strategy.domain.repository;

import com.sism.strategy.domain.indicator.Indicator;
import com.sism.organization.domain.SysOrg;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * IndicatorRepository - 指标仓储接口
 * 定义在领域层，由基础设施层实现
 */
public interface IndicatorRepository {

    /**
     * 根据ID查询指标
     */
    Optional<Indicator> findById(Long id);

    /**
     * 根据指标ID和拥有组织ID查询指标
     * 用于需要先做权限过滤的安全查询场景
     */
    Optional<Indicator> findByIdAndOwnerOrgId(Long id, Long ownerOrgId);

    /**
     * 查询所有指标
     */
    List<Indicator> findAll();

    /**
     * 分页查询所有指标
     */
    Page<Indicator> findAll(Pageable pageable);

    /**
     * 根据目标组织查询指标
     */
    List<Indicator> findByTargetOrg(SysOrg targetOrg);

    /**
     * 根据目标组织ID查询指标
     */
    List<Indicator> findByTargetOrgId(Long targetOrgId);

    /**
     * 根据拥有组织查询指标
     */
    List<Indicator> findByOwnerOrg(SysOrg ownerOrg);

    /**
     * 根据拥有组织ID查询指标
     */
    List<Indicator> findByOwnerOrgId(Long ownerOrgId);

    /**
     * 根据拥有组织ID和目标组织ID查询指标
     * 用于批量撤回操作
     */
    List<Indicator> findByOwnerOrgIdAndTargetOrgId(Long ownerOrgId, Long targetOrgId);

    /**
     * 根据状态查询指标
     */
    List<Indicator> findByStatus(String status);

    /**
     * 根据状态分页查询指标
     */
    Page<Indicator> findByStatus(String status, Pageable pageable);

    /**
     * 根据父指标查询子指标
     */
    List<Indicator> findByParentIndicatorId(Long parentIndicatorId);

    /**
     * 根据任务ID查询指标
     */
    List<Indicator> findByTaskId(Long taskId);

    /**
     * 根据任务ID列表查询指标
     */
    List<Indicator> findByTaskIds(List<Long> taskIds);

    /**
     * 保存指标
     */
    Indicator save(Indicator indicator);

    /**
     * 批量保存指标
     */
    List<Indicator> saveAll(List<Indicator> indicators);

    /**
     * 删除指标
     */
    void delete(Indicator indicator);

    /**
     * 检查指标是否存在
     */
    boolean existsById(Long id);

    /**
     * 查询一级指标（战略到职能）
     */
    List<Indicator> findFirstLevelIndicators();

    /**
     * 查询二级指标（职能到学院）
     */
    List<Indicator> findSecondLevelIndicators();

    /**
     * 根据关键字搜索指标
     */
    List<Indicator> findByKeyword(String keyword);

    /**
     * 根据任务ID列表分页查询指标
     */
    Page<Indicator> findByTaskIds(List<Long> taskIds, Pageable pageable);

    /**
     * 根据年份获取指标（通过 Cycle -> Plan -> Task -> Indicator 关系链）
     */
    Page<Indicator> findByYear(Integer year, Pageable pageable);

    /**
     * 2026-10-26 数据隔离（R13）：按可见组织（目标组织或拥有组织 = 本部门）分页查询
     */
    Page<Indicator> findByYearAndVisibleOrg(Integer year, Long orgId, Pageable pageable);

    /**
     * 2026-10-26 数据隔离（R13）：按可见组织分页查询（不限年份）
     */
    Page<Indicator> findByVisibleOrg(Long orgId, Pageable pageable);

    /**
     * 根据 ID 列表批量查询指标
     */
    List<Indicator> findByIds(List<Long> ids);
}
