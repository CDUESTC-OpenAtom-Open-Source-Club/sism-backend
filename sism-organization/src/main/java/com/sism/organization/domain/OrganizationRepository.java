package com.sism.organization.domain;

import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * OrganizationRepository - 组织仓储接口
 * 定义在领域层，由基础设施层实现
 */
public interface OrganizationRepository {

    /**
     * 根据ID查询组织
     */
    Optional<SysOrg> findById(Long id);

    List<SysOrg> findAllByIds(List<Long> ids);

    /**
     * 查询所有组织
     */
    List<SysOrg> findAll();

    /**
     * 分页查询组织
     */
    Page<SysOrg> findAll(Pageable pageable);

    /**
     * 根据父组织ID查询子组织
     */
    List<SysOrg> findByParentOrgId(Long parentOrgId);

    /**
     * 根据组织类型查询
     */
    List<SysOrg> findByType(OrgType type);

    /**
     * 根据多个组织类型查询
     */
    List<SysOrg> findByTypes(List<OrgType> types);

    /**
     * 根据多个组织类型和激活状态查询
     */
    List<SysOrg> findByTypesAndIsActive(List<OrgType> types, Boolean isActive);

    /**
     * 根据激活状态查询
     */
    List<SysOrg> findByIsActive(Boolean isActive);

    /**
     * 根据层级查询
     */
    List<SysOrg> findByLevel(Integer level);

    /**
     * 根据名称模糊查询
     */
    List<SysOrg> findByNameContaining(String name);

    /**
     * 保存组织
     */
    SysOrg save(SysOrg org);

    /**
     * 删除组织
     */
    void delete(SysOrg org);

    /**
     * 检查组织是否存在
     */
    boolean existsById(Long id);

    /**
     * 查询所有顶级组织
     */
    List<SysOrg> findTopLevelOrgs();
}
