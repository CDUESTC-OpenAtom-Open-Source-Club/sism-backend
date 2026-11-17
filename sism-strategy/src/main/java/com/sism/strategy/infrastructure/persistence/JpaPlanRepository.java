package com.sism.strategy.infrastructure.persistence;

import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaPlanRepository implements PlanRepository {

    private final JpaPlanRepositoryInternal jpaRepository;

    @Override
    public Optional<Plan> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Plan> findAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByIdIn(ids);
    }

    @Override
    public List<Plan> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public Page<Plan> findPage(List<Long> cycleIds, List<String> statuses, Pageable pageable) {
        boolean skipCycleFilter = cycleIds == null || cycleIds.isEmpty();
        if (statuses == null || statuses.isEmpty()) {
            return jpaRepository.findPage(cycleIds, skipCycleFilter, pageable);
        }
        return jpaRepository.findPageByStatus(cycleIds, skipCycleFilter, statuses, pageable);
    }

    @Override
    public List<Plan> findByTargetOrgId(Long targetOrgId) {
        return jpaRepository.findByTargetOrgId(targetOrgId);
    }

    @Override
    public List<Plan> findByCreatedByOrgId(Long createdByOrgId) {
        return jpaRepository.findByCreatedByOrgId(createdByOrgId);
    }

    @Override
    public List<Plan> findByCycleId(Long cycleId) {
        return jpaRepository.findByCycleId(cycleId);
    }

    @Override
    public List<Plan> findByPlanLevel(PlanLevel planLevel) {
        return jpaRepository.findByPlanLevel(planLevel);
    }

    @Override
    public Optional<Plan> findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
            Long cycleId,
            PlanLevel planLevel,
            Long createdByOrgId,
            Long targetOrgId
    ) {
        List<Plan> activePlans = findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                cycleId,
                planLevel,
                createdByOrgId,
                targetOrgId
        );
        if (!activePlans.isEmpty()) {
            return Optional.of(activePlans.get(0));
        }

        return jpaRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                cycleId,
                planLevel,
                createdByOrgId,
                targetOrgId
        );
    }

    @Override
    public List<Plan> findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
            Long cycleId,
            PlanLevel planLevel,
            Long createdByOrgId,
            Long targetOrgId
    ) {
        return jpaRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                cycleId,
                planLevel,
                createdByOrgId,
                targetOrgId
        );
    }

    /**
     * 2026-11-17（学院线粒度改造）：按线身份（含归口部门）取存活计划，命中多条取 id 最大。
     */
    @Override
    public Optional<Plan> findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
            Long cycleId,
            PlanLevel planLevel,
            Long createdByOrgId,
            Long targetOrgId,
            Long functionalOrgId
    ) {
        List<Plan> plans = jpaRepository
                .findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                        cycleId,
                        planLevel,
                        createdByOrgId,
                        targetOrgId,
                        functionalOrgId);
        if (plans == null || plans.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(plans.get(0));
    }

    @Override
    public List<Plan> findByStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByStatusIn(statuses);
    }

    @Override
    public Plan save(Plan plan) {
        return jpaRepository.save(plan);
    }

    @Override
    public Plan saveAndFlush(Plan plan) {
        return jpaRepository.saveAndFlush(plan);
    }

    @Override
    public void delete(Plan plan) {
        jpaRepository.delete(plan);
    }

    @Override
    public boolean existsById(Long id) {
        return jpaRepository.existsById(id);
    }
}
