package com.sism.strategy.domain.repository;

import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * Strategy-owned repository for the planning aggregate.
 */
public interface PlanRepository {

    Optional<Plan> findById(Long id);

    default List<Plan> findAllByIds(List<Long> ids) {
        return List.of();
    }

    List<Plan> findAll();

    Page<Plan> findPage(List<Long> cycleIds, List<String> statuses, Pageable pageable);

    List<Plan> findByTargetOrgId(Long targetOrgId);

    List<Plan> findByCreatedByOrgId(Long createdByOrgId);

    List<Plan> findByCycleId(Long cycleId);

    List<Plan> findByPlanLevel(PlanLevel planLevel);

    Optional<Plan> findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
            Long cycleId,
            PlanLevel planLevel,
            Long createdByOrgId,
            Long targetOrgId
    );

    List<Plan> findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
            Long cycleId,
            PlanLevel planLevel,
            Long createdByOrgId,
            Long targetOrgId
    );

    /**
     * 2026-11-17（学院线粒度改造）：按「线身份」查计划。
     *
     * <p>线身份 = (cycle_id, plan_level, created_by_org_id=代发通道, target_org_id=目标学院,
     * functional_org_id=归口部门)。一个学院在同一考核年度可以有多个归口部门各自的线，
     * 每条线独立状态、独立指标；查计划必须带归口维度，否则会命中别的线
     * （典型故障：教务处那条线已下发，数字校园那条线导不进去）。
     *
     * <p><b>禁止</b>把 functionalOrgId 的条件写成 {@code IS NULL OR = ?}：
     * 那会误命中 legacy 空壳（functional_org_id 为空的存量计划），把别的线的历史计划
     * 当成本次要用的线。
     *
     * @param functionalOrgId 归口部门；null 时不命中任何行（语义 = 指定归口，null 非通配）
     * @return 命中的线（命中多条时取 id 最大的一条）；没有则 empty
     */
    Optional<Plan> findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
            Long cycleId,
            PlanLevel planLevel,
            Long createdByOrgId,
            Long targetOrgId,
            Long functionalOrgId
    );

    List<Plan> findByStatuses(List<String> statuses);

    Plan save(Plan plan);

    Plan saveAndFlush(Plan plan);

    void delete(Plan plan);

    boolean existsById(Long id);
}
