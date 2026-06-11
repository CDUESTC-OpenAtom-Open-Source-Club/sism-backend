package com.sism.strategy.application;

import com.sism.organization.domain.SysOrg;
import com.sism.shared.domain.model.base.DomainEvent;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import com.sism.shared.infrastructure.event.EventStore;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanStatus;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.repository.TaskRepository;
import org.hibernate.Hibernate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StrategyApplicationService {

    private final DomainEventPublisher eventPublisher;
    private final EventStore eventStore;
    private final IndicatorRepository indicatorRepository;
    private final TaskRepository taskRepository;
    private final PlanRepository planRepository;
    private final BasicTaskWeightValidationService basicTaskWeightValidationService;

    private static final String DISTRIBUTED_PLAN_MUTATION_BLOCKED_MESSAGE =
            "当前任务已下发，不能重复导入或下发";

    @Transactional
    public Indicator createIndicator(String indicatorDesc, SysOrg ownerOrg, SysOrg targetOrg, String indicatorType) {
        Indicator indicator = Indicator.create(indicatorDesc, ownerOrg, targetOrg, indicatorType);
        indicator.validate();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    @Transactional
    public Indicator createIndicator(
            String indicatorDesc,
            SysOrg ownerOrg,
            SysOrg targetOrg,
            Long taskId,
            Long parentIndicatorId,
            String indicatorType,
            BigDecimal weightPercent,
            Integer sortOrder,
            String remark,
            Integer progress) {
        ensurePlanNotDistributedForTask(taskId);

        Indicator indicator = Indicator.create(indicatorDesc, ownerOrg, targetOrg, indicatorType);

        if (taskId != null) {
            indicator.setTaskId(taskId);
        }
        if (parentIndicatorId != null) {
            Indicator parent = indicatorRepository.findById(parentIndicatorId)
                    .orElseThrow(() -> new IllegalArgumentException("Parent indicator not found: " + parentIndicatorId));
            indicator.setParent(parent);
        }
        if (weightPercent != null) {
            indicator.setWeightPercent(weightPercent);
        }
        if (sortOrder != null) {
            indicator.setSortOrder(sortOrder);
        }
        if (remark != null) {
            indicator.setRemark(remark);
        }
        if (progress != null) {
            indicator.setProgress(progress);
        }

        indicator.setLevel(indicator.calculateLevel());
        indicator.validate();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    @Transactional
    public Indicator submitIndicatorForReview(Indicator indicator) {
        indicator.submitForReview();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    @Transactional
    public Indicator approveIndicator(Indicator indicator) {
        indicator.approve();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    @Transactional
    public Indicator rejectIndicator(Indicator indicator) {
        indicator.reject();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    @Transactional
    public Indicator distributeIndicator(Long id) {
        return distributeIndicator(id, null, null);
    }

    @Transactional
    public Indicator distributeIndicator(Long id, SysOrg targetOrg, String customDesc) {
        Indicator indicator = indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));

        Long resolvedTargetOrgId = targetOrg != null
                ? targetOrg.getId()
                : indicator.getTargetOrg() != null ? indicator.getTargetOrg().getId() : null;

        validatePlanBasicWeightBeforeDistribution(indicator, resolvedTargetOrgId);

        if (targetOrg != null) {
            indicator.setTargetOrg(targetOrg);
            indicator.setLevel(indicator.calculateLevel());
        }
        if (customDesc != null && !customDesc.trim().isEmpty()) {
            indicator.setIndicatorDesc(customDesc.trim());
        }
        indicator.distribute();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    private void validatePlanBasicWeightBeforeDistribution(Indicator indicator, Long targetOrgId) {
        if (indicator.getParentIndicatorId() != null || indicator.getTaskId() == null) {
            return;
        }

        StrategicTask task = taskRepository.findById(indicator.getTaskId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found for indicator: " + indicator.getTaskId()));

        basicTaskWeightValidationService.validatePlanBasicWeight(task.getPlanId(), targetOrgId);
    }

    @Transactional
    public Indicator withdrawIndicator(Long id, String reason) {
        Indicator indicator = indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));
        indicator.withdraw();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    /**
     * 批量撤回指标
     * 撤回指定ownerOrg发给targetOrg的所有指标
     *
     * @param ownerOrgId 发布部门ID
     * @param targetOrgId 目标学院ID
     * @param reason 撤回原因
     * @return 批量撤回结果
     */
    @Transactional
    public com.sism.strategy.interfaces.rest.IndicatorController.BatchWithdrawResponse batchWithdrawIndicators(
            Long ownerOrgId,
            Long targetOrgId,
            String reason) {

        // 查找所有符合条件的指标
        List<Indicator> indicators = indicatorRepository.findByOwnerOrgIdAndTargetOrgId(ownerOrgId, targetOrgId);

        int successCount = 0;
        int failedCount = 0;
        List<Long> withdrawnIndicatorIds = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();

        for (Indicator indicator : indicators) {
            try {
                // 只撤回已进入下发链路的指标（PENDING, DISTRIBUTED 状态）
                IndicatorStatus status = indicator.getStatus();
                if (status == IndicatorStatus.DISTRIBUTED ||
                    status == IndicatorStatus.PENDING) {

                    indicator.withdraw();
                    indicator = indicatorRepository.save(indicator);
                    publishAndSaveEvents(indicator);

                    withdrawnIndicatorIds.add(indicator.getId());
                    successCount++;
                } else {
                    // 草稿状态的指标跳过，不计入失败
                    continue;
                }
            } catch (Exception e) {
                failedCount++;
                errors.add("指标 " + indicator.getId() + ": " + e.getMessage());
            }
        }

        return new com.sism.strategy.interfaces.rest.IndicatorController.BatchWithdrawResponse(
                indicators.size(),
                successCount,
                failedCount,
                withdrawnIndicatorIds,
                errors
        );
    }

    public Indicator getIndicatorById(Long id) {
        return indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));
    }

    public Indicator getIndicatorByIdAndOwnerOrgId(Long id, Long ownerOrgId) {
        return indicatorRepository.findByIdAndOwnerOrgId(id, ownerOrgId)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found for owner org: " + id));
    }

    public List<Indicator> getAllIndicators() {
        return indicatorRepository.findAll();
    }

    public Page<Indicator> getIndicators(Pageable pageable) {
        return indicatorRepository.findAll(pageable);
    }

    public Page<Indicator> getIndicatorsByStatus(String status, Pageable pageable) {
        return indicatorRepository.findByStatus(status, pageable);
    }

    /**
     * 根据年份获取指标
     * 通过 Cycle -> Plan -> Task -> Indicator 关系链过滤
     * 使用原生 SQL 直接 JOIN 获取正确的指标
     *
     * 性能优化：在返回前初始化懒加载的关联实体，避免 N+1 查询
     *
     * @param year 年份
     * @param pageable 分页参数
     * @return 分页指标列表
     */
    public Page<Indicator> getIndicatorsByYear(Integer year, Pageable pageable) {
        Page<Indicator> result = indicatorRepository.findByYear(year, pageable);
        // 初始化懒加载的关联实体，避免 N+1 查询
        result.getContent().forEach(indicator -> {
            Hibernate.initialize(indicator.getOwnerOrg());
            Hibernate.initialize(indicator.getTargetOrg());
        });
        return result;
    }

    public List<Indicator> searchIndicators(String keyword) {
        return indicatorRepository.findByKeyword(keyword);
    }

    public List<Indicator> getIndicatorsByTaskId(Long taskId) {
        return indicatorRepository.findByTaskId(taskId);
    }

    public List<Indicator> getRootIndicatorsByTaskId(Long taskId) {
        return indicatorRepository.findByTaskId(taskId).stream()
                .filter(indicator -> indicator.getParentIndicatorId() == null)
                .toList();
    }

    public List<Indicator> getIndicatorsByOwnerOrgId(Long ownerOrgId) {
        return indicatorRepository.findByOwnerOrgId(ownerOrgId);
    }

    public List<Indicator> getIndicatorsByTargetOrgId(Long targetOrgId) {
        return indicatorRepository.findByTargetOrgId(targetOrgId);
    }

    public List<Indicator> getDistributedIndicators(Long parentIndicatorId) {
        return indicatorRepository.findByParentIndicatorId(parentIndicatorId);
    }

    @Transactional
    public Indicator updateIndicator(
            Long id,
            String indicatorDesc,
            BigDecimal weightPercent,
            Integer progress,
            Integer sortOrder,
            String remark,
            Long taskId,
            SysOrg ownerOrg,
            SysOrg targetOrg) {
        Indicator indicator = indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));

        ensurePlanNotDistributedForTask(indicator.getTaskId());
        if (taskId != null && !taskId.equals(indicator.getTaskId())) {
            ensurePlanNotDistributedForTask(taskId);
        }

        if (indicatorDesc != null) {
            String normalizedDesc = indicatorDesc.trim();
            if (normalizedDesc.isEmpty()) {
                throw new IllegalArgumentException("指标描述不能为空");
            }
            indicator.setIndicatorDesc(normalizedDesc);
        }
        if (weightPercent != null) {
            if (weightPercent.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("权重必须为正数");
            }
            indicator.setWeightPercent(weightPercent);
        }
        if (progress != null) {
            indicator.setProgress(progress);
        }
        if (sortOrder != null) {
            if (sortOrder < 0) {
                throw new IllegalArgumentException("排序顺序不能为负数");
            }
            indicator.setSortOrder(sortOrder);
        }
        if (remark != null) {
            indicator.setRemark(remark);
        }
        if (taskId != null) {
            indicator.setTaskId(taskId);
        }
        if (ownerOrg != null) {
            indicator.setOwnerOrg(ownerOrg);
        }
        if (targetOrg != null) {
            indicator.setTargetOrg(targetOrg);
        }

        indicator.setLevel(indicator.calculateLevel());
        indicator.setUpdatedAt(LocalDateTime.now());
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    @Transactional
    public void deleteIndicator(Long id) {
        Indicator indicator = indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));
        ensurePlanNotDistributedForTask(indicator.getTaskId());
        indicator.archive();
        indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
    }

    private void ensurePlanNotDistributedForTask(Long taskId) {
        if (taskId == null) {
            return;
        }

        StrategicTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        Plan plan = planRepository.findById(task.getPlanId())
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + task.getPlanId()));
        if (PlanStatus.fromRaw(plan.getStatus()) == PlanStatus.DISTRIBUTED) {
            throw new DistributedPlanMutationBlockedException(DISTRIBUTED_PLAN_MUTATION_BLOCKED_MESSAGE);
        }
    }

    @Transactional
    public Indicator breakdownIndicator(Long id) {
        Indicator indicator = indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));

        if (!indicator.canBreakdown()) {
            throw new IllegalStateException("Indicator cannot be broken down");
        }

        indicator.markAsBrokenDown();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    @Transactional
    public Indicator activateIndicator(Long id) {
        Indicator indicator = indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));

        indicator.activate();
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    @Transactional
    public Indicator terminateIndicator(Long id, String reason) {
        Indicator indicator = indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));

        indicator.terminate(reason);
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    private void publishAndSaveEvents(com.sism.shared.domain.model.base.AggregateRoot<?> aggregate) {
        List<DomainEvent> events = aggregate.getDomainEvents();

        for (DomainEvent event : events) {
            eventStore.save(event);
        }

        eventPublisher.publishAll(events);

        aggregate.clearEvents();
    }
}
