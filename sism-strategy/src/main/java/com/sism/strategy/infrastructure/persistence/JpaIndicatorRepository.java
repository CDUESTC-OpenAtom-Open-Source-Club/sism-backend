package com.sism.strategy.infrastructure.persistence;

import com.sism.organization.domain.SysOrg;
import com.sism.organization.domain.OrgType;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.indicator.IndicatorLevel;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.strategy.domain.repository.IndicatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaIndicatorRepository implements IndicatorRepository {

    private final JpaIndicatorRepositoryInternal jpaRepository;

    @Override
    public Optional<Indicator> findById(Long id) {
        return jpaRepository.findByIdAndIsDeletedFalse(id);
    }

    @Override
    public Optional<Indicator> findByIdAndOwnerOrgId(Long id, Long ownerOrgId) {
        if (id == null || ownerOrgId == null) {
            return Optional.empty();
        }
        return jpaRepository.findByIdAndOwnerOrgIdAndIsDeletedFalse(id, ownerOrgId);
    }

    @Override
    public List<Indicator> findAll() {
        return jpaRepository.findAllByIsDeletedFalse();
    }

    @Override
    public Page<Indicator> findAll(Pageable pageable) {
        return jpaRepository.findAllByIsDeletedFalse(pageable);
    }

    @Override
    public List<Indicator> findByTargetOrg(SysOrg targetOrg) {
        if (targetOrg == null || targetOrg.getId() == null) {
            return List.of();
        }
        return findByTargetOrgId(targetOrg.getId());
    }

    @Override
    public List<Indicator> findByTargetOrgId(Long targetOrgId) {
        return jpaRepository.findByTargetOrgIdAndIsDeletedFalse(targetOrgId);
    }

    @Override
    public List<Indicator> findByOwnerOrg(SysOrg ownerOrg) {
        if (ownerOrg == null || ownerOrg.getId() == null) {
            return List.of();
        }
        return findByOwnerOrgId(ownerOrg.getId());
    }

    @Override
    public List<Indicator> findByOwnerOrgId(Long ownerOrgId) {
        return jpaRepository.findByOwnerOrgIdAndIsDeletedFalse(ownerOrgId);
    }

    @Override
    public List<Indicator> findByStatus(String status) {
        IndicatorStatus normalizedStatus = normalizeStatus(status);
        if (normalizedStatus == null) {
            return List.of();
        }
        return jpaRepository.findByStatusAndIsDeletedFalse(normalizedStatus);
    }

    @Override
    public Page<Indicator> findByStatus(String status, Pageable pageable) {
        IndicatorStatus normalizedStatus = normalizeStatus(status);
        if (normalizedStatus == null) {
            return Page.empty(pageable);
        }
        return jpaRepository.findByStatusAndIsDeletedFalse(normalizedStatus, pageable);
    }

    @Override
    public List<Indicator> findByParentIndicatorId(Long parentIndicatorId) {
        return jpaRepository.findByParentIndicatorIdAndIsDeletedFalse(parentIndicatorId);
    }

    @Override
    public List<Indicator> findByTaskId(Long taskId) {
        return jpaRepository.findByTaskIdAndIsDeletedFalse(taskId);
    }

    @Override
    public List<Indicator> findByTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByTaskIds(taskIds);
    }

    @Override
    public Indicator save(Indicator indicator) {
        return jpaRepository.save(indicator);
    }

    @Override
    public List<Indicator> saveAll(List<Indicator> indicators) {
        if (indicators == null || indicators.isEmpty()) {
            return List.of();
        }
        return jpaRepository.saveAll(indicators);
    }

    @Override
    public void delete(Indicator indicator) {
        jpaRepository.delete(indicator);
    }

    @Override
    public boolean existsById(Long id) {
        return jpaRepository.existsByIdAndIsDeletedFalse(id);
    }

    @Override
    public List<Indicator> findFirstLevelIndicators() {
        return jpaRepository.findFirstLevelIndicators(OrgType.functional).stream()
                .filter(indicator -> indicator.getLevel() == IndicatorLevel.FIRST)
                .toList();
    }

    @Override
    public List<Indicator> findSecondLevelIndicators() {
        return jpaRepository.findSecondLevelIndicators(OrgType.functional).stream()
                .filter(indicator -> indicator.getLevel() == IndicatorLevel.SECOND)
                .toList();
    }

    @Override
    public List<Indicator> findByKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByIndicatorDescContainingIgnoreCaseAndIsDeletedFalse(keyword.trim());
    }

    @Override
    public Page<Indicator> findByTaskIds(List<Long> taskIds, Pageable pageable) {
        return jpaRepository.findByTaskIds(taskIds, pageable);
    }

    @Override
    public Page<Indicator> findByYear(Integer year, Pageable pageable) {
        return jpaRepository.findByYear(year, pageable);
    }

    @Override
    public Page<Indicator> findByYearAndVisibleOrg(Integer year, Long orgId, Pageable pageable) {
        return jpaRepository.findByYearAndVisibleOrg(year, orgId, pageable);
    }

    @Override
    public Page<Indicator> findByVisibleOrg(Long orgId, Pageable pageable) {
        return jpaRepository.findByVisibleOrg(orgId, pageable);
    }

    @Override
    public List<Indicator> findByOwnerOrgIdAndTargetOrgId(Long ownerOrgId, Long targetOrgId) {
        return jpaRepository.findByOwnerOrgIdAndTargetOrgIdAndIsDeletedFalse(ownerOrgId, targetOrgId);
    }

    @Override
    public List<Indicator> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByIdInAndIsDeletedFalse(ids);
    }

    private IndicatorStatus normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return IndicatorStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
