package com.sism.strategy.application;

import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.domain.model.base.DomainEvent;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import com.sism.shared.infrastructure.event.EventStore;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.plan.PlanStatus;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.task.TaskType;
import com.sism.task.domain.repository.TaskRepository;
import org.hibernate.Hibernate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyApplicationService {

    private final DomainEventPublisher eventPublisher;
    private final EventStore eventStore;
    private final IndicatorRepository indicatorRepository;
    private final TaskRepository taskRepository;
    private final PlanRepository planRepository;
    private final BasicTaskWeightValidationService basicTaskWeightValidationService;
    /** 2026-10-22：「可撤销未提交新增」判定需要直查 mutation_status 与异动快照 */
    private final JdbcTemplate jdbcTemplate;
    /** 导入/下发入口专用：已下发计划不能重复导入或再次下发 */
    private static final String DISTRIBUTED_PLAN_MUTATION_BLOCKED_MESSAGE =
            "当前任务已下发，不能重复导入或下发";

    @Transactional
    public Indicator createIndicator(String indicatorDesc, SysOrg ownerOrg, SysOrg targetOrg, String indicatorType) {
        assertDispatchScopeAllowed(ownerOrg, targetOrg);
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
        return createIndicator(
                indicatorDesc,
                ownerOrg,
                targetOrg,
                taskId,
                parentIndicatorId,
                indicatorType,
                weightPercent,
                sortOrder,
                remark,
                progress,
                null
        );
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
            Integer progress,
            Long cycleId) {
        // 2026-11-05（学院指标直发改造）：「职能部门 → 学院」链路已取消，统一由战略部下发。
        // 在最前面拦截，避免旧旁路（直接建指标 / 批量分发）把这条链路复活且前端不留痕迹。
        assertDispatchScopeAllowed(ownerOrg, targetOrg);
        Indicator parent = null;
        Long resolvedTaskId = taskId;

        // 2026-10-20 用户定案：异动期间不再锁其他模块（填报/导入/下发等全部放开，人为处理）。
        // 原异动锁口径（target/owner 任一命中即全锁）导致战略部对任意部门的导入被无关异动
        // 拦截（线上实例：保卫处指标异动锁死后勤资产处导入），本处检查整体移除。
        if (parentIndicatorId != null) {
            parent = indicatorRepository.findById(parentIndicatorId)
                    .orElseThrow(() -> new IllegalArgumentException("Parent indicator not found: " + parentIndicatorId));
            resolvedTaskId = resolveTaskIdForChildIndicator(taskId, cycleId, parent, ownerOrg, targetOrg);
        }
        // 2026-10-20 用户定案：已下发计划允许战略部继续新增指标（走异动审批链），
        // 因此这里不再调用 ensurePlanNotDistributedForTask 拦截；
        // 导入路径的「不能重复导入」守卫保留在导入服务内（BusinessImportApplicationService）。
        // 2026-10-24 用户定案：计划审批中（PENDING，下发审批待负责人/校领导处理）内容必须冻结——
        // 审批的对象就是当前表内容，此时允许新增行会让审批失去意义。DRAFT 可继续填报，
        // DISTRIBUTED 走异动链（既有定案），均不受影响。
        assertPlanNotUnderApprovalForTask(resolvedTaskId);

        Indicator indicator = Indicator.create(indicatorDesc, ownerOrg, targetOrg, indicatorType);

        if (resolvedTaskId != null) {
            indicator.setTaskId(resolvedTaskId);
        }
        if (parent != null) {
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
        // 2026-10-30（D10）：基础性权重合计校验前移到单条新增指标。
        // 校验放在 save 之后、发布事件之前：新指标的权重必须计入合计才有意义；
        // 校验不通过时抛异常，@Transactional 会把本次插入整体回滚（等于「没落库」）。
        validateBasicWeightForDistributedPlan(resolvedTaskId, targetOrg);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    /**
     * 2026-11-05（学院指标直发改造）：「职能部门 → 二级学院」的分发链路已整体取消，
     * 学院子指标统一由战略发展部（org 35）直接下发。
     *
     * <p>判定特征就是<b>归属组织是职能部门 + 目标组织是二级学院</b>——这是旧链路的唯一指纹。
     * 所有写入/下发入口都过这一道，避免旧旁路（手工建指标、批量分发、复用已有指标改投）
     * 把链路复活；复活后前端没有任何入口能撤回，用户也无从察觉。
     *
     * <p>注意：战略部 → 职能部门、战略部 → 学院都<b>不</b>命中该判定，不受影响。
     *
     * <p>2026-11-05（minor 口径核对）：这里只按<b>组织类型</b>判定，不绑定具体 org id 是有意的——
     * 本守卫要禁的是「职能部门 → 学院」这一<b>类关系</b>（所有职能部门都禁），而不是「只有 org 35
     * 能下发」。「只有战略部能向学院下发」是另一条规则，由计划级闸门兜底
     * （{@code PlanApplicationService.assertCollegeDispatchCreatedByStrategyOrg}：
     * FUNC_TO_COLLEGE 计划的创建组织必须是战略发展部），不依赖这里的类型判定。
     *
     * <p>2026-11-05（MA4）：归属组织缺失一律拒绝。原口径是「ownerOrg 为 null 就放行」，
     * 与批量分发侧既有口径（{@code BatchIndicatorDistributionApplicationService}：
     * 查不到归属组织一律拒绝）相反；而且归属缺失时根本无法判断这是不是旧链路，
     * 静默放行等于把守卫开了一个口子。目标组织缺失则无法判断是否指向学院，
     * 保留放行（不是旧链路的指纹），真正的兜底由生效目标判定（distributeIndicator）承担。
     */
    public void assertDispatchScopeAllowed(SysOrg ownerOrg, SysOrg targetOrg) {
        if (ownerOrg == null) {
            throw new IllegalArgumentException(
                    "指标归属组织缺失，无法判定下发范围；请检查指标归属组织后重试");
        }
        if (targetOrg == null) {
            return;
        }
        if (ownerOrg.getType() == OrgType.functional && targetOrg.getType() == OrgType.academic) {
            throw new IllegalArgumentException(
                    "学院指标下发已统一由战略发展部发起，职能部门不再向学院分发指标");
        }
    }

    /**
     * 2026-10-30（D10）：把「基础性权重合计必须为 100」的校验前移到单条新增指标。
     *
     * <p>触发条件：该指标所属计划已处于 <b>DISTRIBUTED（已下发）</b> 状态。
     * 已下发计划下新增指标会立刻改变生效口径的合计，必须保持 = 100；
     * DRAFT 计划允许中间态——导入是批量逐条建指标的，中间态合计必然 ≠ 100，
     * 若无差别加校验会把导入路径全部打挂。
     *
     * <p>口径与报错文案完全沿用 {@link BasicTaskWeightValidationService} 既有实现，
     * 这里不另算一套合计口径。
     */
    private void validateBasicWeightForDistributedPlan(Long taskId, SysOrg targetOrg) {
        if (taskId == null || targetOrg == null || targetOrg.getId() == null) {
            return;
        }
        Plan plan = taskRepository.findById(taskId)
                .map(StrategicTask::getPlanId)
                .flatMap(planRepository::findById)
                .orElse(null);
        if (plan == null || PlanStatus.fromRaw(plan.getStatus()) != PlanStatus.DISTRIBUTED) {
            return;
        }
        basicTaskWeightValidationService.validatePlanBasicWeight(plan.getId(), targetOrg.getId());
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

        // 2026-11-05（学院指标直发改造）：把已有指标改投到学院，同样属于「向学院分发」。
        // 归属组织是职能部门 + 目标组织是学院 = 旧链路特征，拒绝。
        // 2026-11-05（BL2 修复）：判定必须用「生效目标」而不是入参——targetOrg 为 null 时
        // （POST /indicators/{id}/distribute 的 targetOrgId 可选、distributeIndicator(id) 重载）
        // 生效目标会回落到指标自身的 target_org_id；旧口径拿入参判定，null 直接放行 →
        // 存量「职能部门 → 学院」指标仍能照旧下发给学院，守卫等于失效。
        SysOrg effectiveTargetOrg = targetOrg != null ? targetOrg : indicator.getTargetOrg();
        assertDispatchScopeAllowed(indicator.getOwnerOrg(), effectiveTargetOrg);

        // 2026-11-05（R7）：禁止把「战略部下发的核心指标」本身改投到学院。
        //
        // 核心指标是「战略部下发给职能部门」的一级指标（owner=战略部、target=职能部门、
        // 无父级）。把它直接搬到学院名下会破坏需求「学院指标由战略部按学院直发」的结构，
        // 也破坏权重合计口径。正确做法是产生一条新的学院指标（owner=战略部、target=学院）。
        //
        // 2026-11-08（学院指标单层口径）：判定特征<b>不能再只看 parent == null</b>——
        // 学院指标现在也没有父级（单层直发），只用 parent 判定会把「对一条学院指标再次下发」
        // 误拦成「核心指标改投」，新口径下这条路会 100% 失败。
        // 真正的核心指标特征是「当前目标不是学院（战略部 → 职能部门）」，
        // 已经是学院指标的记录（target = 学院）不属于此处要拦的情况。
        boolean currentTargetIsCollege = indicator.getTargetOrg() != null
                && indicator.getTargetOrg().getType() == OrgType.academic;
        if (effectiveTargetOrg != null
                && effectiveTargetOrg.getType() == OrgType.academic
                && !currentTargetIsCollege
                && indicator.getParentIndicatorId() == null) {
            throw new IllegalArgumentException(
                    "核心指标不能直接改投到二级学院，请基于该核心指标新增学院指标");
        }

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
        syncPlanContainerOnIndicatorDistributed(indicator);
        return indicator;
    }

    /**
     * 指标下发后同步计划容器状态（缺陷修复：此前只改指标不改计划，
     * 导致"指标已下发、计划仍 DRAFT"，职能部门因计划状态闸门看不到已下发指标）。
     * 仅推进 DRAFT 计划；PENDING 走工作流、已 DISTRIBUTED 幂等跳过。
     */
    private void syncPlanContainerOnIndicatorDistributed(Indicator indicator) {
        if (indicator.getTaskId() == null) {
            return;
        }
        taskRepository.findById(indicator.getTaskId())
                .map(StrategicTask::getPlanId)
                .flatMap(planRepository::findById)
                .ifPresent(plan -> {
                    if (plan.promoteFromDraftOnIndicatorDistributed()) {
                        Plan saved = planRepository.save(plan);
                        publishAndSaveEvents(saved);
                    }
                });
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

    /**
     * 2026-10-26 数据隔离（R13）：按可见组织查询指标。
     * 部门账号（职能部门/二级学院）只能看到目标组织或拥有组织为本部门的指标；
     * 战略发展部（org35）保留全量视图。
     */
    public Page<Indicator> getIndicatorsByVisibleOrg(Integer year, Long orgId, boolean fullAccess, Pageable pageable) {
        Page<Indicator> result;
        if (fullAccess) {
            result = year != null
                    ? indicatorRepository.findByYear(year, pageable)
                    : indicatorRepository.findAll(pageable);
        } else if (year != null) {
            result = indicatorRepository.findByYearAndVisibleOrg(year, orgId, pageable);
        } else {
            result = indicatorRepository.findByVisibleOrg(orgId, pageable);
        }
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

    /**
     * 任务下的根指标（D2 孤立指标容忍，会议定案「父级缺失不报错、当孤立指标」）：
     * parent 为空，或父指标已删除/不存在（软删）的，一律视为根指标返回，
     * 避免父级被删后子指标在前端凭空消失。
     */
    public List<Indicator> getRootIndicatorsByTaskId(Long taskId) {
        List<Indicator> all = indicatorRepository.findByTaskId(taskId);
        Map<Long, Indicator> byId = all.stream()
                .collect(Collectors.toMap(Indicator::getId, indicator -> indicator, (a, b) -> a));
        return all.stream()
                .filter(indicator -> {
                    Long parentId = indicator.getParentIndicatorId();
                    if (parentId == null) {
                        return true;
                    }
                    Indicator parent = byId.get(parentId);
                    if (parent == null) {
                        // 父不在该任务集合：可能跨任务或已被删，以库内现状为准
                        return indicatorRepository.findById(parentId)
                                .map(p -> Boolean.TRUE.equals(p.getIsDeleted()))
                                .orElse(true);
                    }
                    return Boolean.TRUE.equals(parent.getIsDeleted());
                })
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

        // 2026-10-20 用户定案：已下发计划的指标修改不再在此拦截——
        // 界面修改走异动草稿（点「发起异动」统一校验权重并走审批链），
        // 该接口本身保留给审批通过后的落库与内部调用。

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

        // 2026-11-05（学院指标直发改造）：本接口可改归属组织与目标组织，
        // 也能把职能部门指标改投到学院——与新建指标同属旧链路的复活入口，必须拦。
        assertDispatchScopeAllowed(indicator.getOwnerOrg(), indicator.getTargetOrg());

        indicator.setLevel(indicator.calculateLevel());
        indicator.setUpdatedAt(LocalDateTime.now());
        indicator = indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
        return indicator;
    }

    /**
     * 2026-10-31（P-02 后端兜底）用户面直改守卫：已下发/审批中计划的指标不允许
     * 经 PUT /indicators/{id} 直接修改，必须走异动审批链（2026-10-20 定案的
     * 「界面修改走异动草稿」口径的后端兜底——前端不再提供直改入口，但接口本身此前不设防）。
     *
     * <p><b>层级选择</b>：本守卫只挂在用户面入口 IndicatorController#updateIndicator 上，
     * 不放进 {@link #updateIndicator} 本体——后者同时是 Excel 导入覆盖更新
     * （BusinessImportApplicationService）与内部落库的共用通道，2026-10-20 定案已明确
     * 「已下发计划允许导入继续更新」，在服务层拦截会把内部路径一并打挂。
     *
     * <p>判定口径：
     * <ul>
     *   <li>指标所属计划链状态为 DISTRIBUTED（已下发）或 PENDING（下发审批中，
     *       沿用 2026-10-24「审批中内容冻结」口径）→ 拦截；</li>
     *   <li>存在在审异动（PLAN_MUTATION_STRATEGY 链、entity=本指标、IN_REVIEW，
     *       与 IndicatorMutationService#assertNoActiveMutationFlow 完全同口径）
     *       → 同样拦截，文案区分「异动审批进行中」。保守选择也拦：异动审批审的
     *       是当前内容快照，审批期间直改会让在审快照失真、两条链互相踩；</li>
     *   <li>计划未下发（DRAFT/RETURNED）或任务/计划链缺失 → 放行（草稿可自由编辑）。</li>
     * </ul>
     * 拦截统一抛 {@link com.sism.exception.BusinessConflictException}（全局处理器
     * 原文透传、HTTP 409），由调用方 Controller 无需再捕获转换。
     */
    public void assertIndicatorEditableFromUserInterface(Long indicatorId) {
        if (indicatorId == null) {
            return;
        }
        Indicator indicator = indicatorRepository.findById(indicatorId)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + indicatorId));
        Long planId = indicator.getTaskId() == null
                ? null
                : taskRepository.findById(indicator.getTaskId())
                        .map(StrategicTask::getPlanId)
                        .orElse(null);
        if (planId == null) {
            return;
        }
        PlanStatus planStatus = planRepository.findById(planId)
                .map(plan -> PlanStatus.fromRaw(plan.getStatus()))
                .orElse(null);
        if (planStatus != PlanStatus.DISTRIBUTED && planStatus != PlanStatus.PENDING) {
            return;
        }
        if (hasActiveIndicatorMutationFlow(indicatorId)) {
            throw new com.sism.exception.BusinessConflictException(
                    "该指标的异动审批正在进行中，暂不能直接修改；"
                            + "请先在审批中心处理完当前异动流程（通过或退回）后再操作");
        }
        throw new com.sism.exception.BusinessConflictException("已下发指标的修改请通过异动审批进行");
    }

    /**
     * P-02 在审异动判定：与 IndicatorMutationService#assertNoActiveMutationFlow
     * 完全同口径——PLAN_MUTATION_STRATEGY 链、entity_type=INDICATOR、
     * entity_id=本指标、实例状态 IN_REVIEW。
     */
    private boolean hasActiveIndicatorMutationFlow(Long indicatorId) {
        Integer activeCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                WHERE ai.entity_type = 'INDICATOR'
                  AND ai.entity_id = ?
                  AND ai.status = 'IN_REVIEW'
                """,
                Integer.class, indicatorId);
        return activeCount != null && activeCount > 0;
    }

    @Transactional
    public void deleteIndicator(Long id) {
        Indicator indicator = indicatorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Indicator not found: " + id));
        try {
            ensurePlanNotDistributedForTask(indicator.getTaskId());
        } catch (DistributedPlanMutationBlockedException ex) {
            // 2026-10-22：已下发计划里「新增行」刚落库、从未提交过异动审批的草稿指标
            // 允许直接撤销——「取消异动」/删除草稿必须能真正删掉，
            // 否则按钮永远 409 失败并把指标留成孤儿（还计入权重合计卡死后续异动）。
            // 已进入过审批链（有异动快照）或正在审批中的指标维持原守卫。
            if (!isRevocablePendingCreate(indicator)) {
                throw ex;
            }
        }
        indicator.archive();
        indicatorRepository.save(indicator);
        publishAndSaveEvents(indicator);
    }

    /**
     * 2026-10-22：判断指标是否为「可撤销的未提交新增」——同时满足：
     * ① 仍为草稿状态（DRAFT，从未转正/下发）；
     * ② 当前没有异动标记（不在审批中）；
     * ③ 从未写过异动快照（audit_log 无记录，即从未提交过异动审批）。
     * 用于在「已下发计划禁止直接删除」守卫下放行「撤销一次尚未提交的新增」。
     */
    private boolean isRevocablePendingCreate(Indicator indicator) {
        if (indicator.getStatus() != IndicatorStatus.DRAFT || indicator.getId() == null) {
            return false;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT i.mutation_status,
                       EXISTS (
                           SELECT 1 FROM public.audit_log a
                           WHERE a.entity_type = 'INDICATOR' AND a.entity_id = i.id
                       ) AS has_history
                FROM public.indicator i
                WHERE i.id = ?
                """,
                indicator.getId());
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        return row.get("mutation_status") == null && !Boolean.TRUE.equals(row.get("has_history"));
    }

    private Long resolveTaskIdForChildIndicator(
            Long requestedTaskId,
            Long requestedCycleId,
            Indicator parent,
            SysOrg ownerOrg,
            SysOrg targetOrg) {
        // 2026-11-05（学院指标直发改造）：本方法会按「归属组织 + 目标组织」创建 FUNC_TO_COLLEGE
        // 下游计划，是旧「职能部门向学院分发」链路的自动复活入口。归属组织是职能部门、
        // 目标组织是学院时必须拒绝——否则计划会被建出来，指标也会落到学院名下。
        // 2026-11-05（MA4）：归属/目标缺失的校验提前到守卫之前，保持既有报错文案
        // （守卫现在对归属缺失直接拒绝，先报「责任组织不能为空」更贴近调用方预期）。
        if (ownerOrg == null || ownerOrg.getId() == null) {
            throw new IllegalArgumentException("责任组织不能为空");
        }
        if (targetOrg == null || targetOrg.getId() == null) {
            throw new IllegalArgumentException("目标组织不能为空");
        }
        assertDispatchScopeAllowed(ownerOrg, targetOrg);
        StrategicTask requestedTask = null;
        Plan requestedPlan = null;

        if (requestedTaskId != null) {
            requestedTask = taskRepository.findById(requestedTaskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + requestedTaskId));
            Long requestedPlanId = requestedTask.getPlanId();
            requestedPlan = planRepository.findById(requestedPlanId)
                    .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + requestedPlanId));

            // 只有请求的 task 已属于本次下发的下游计划（同一 FUNC_TO_COLLEGE 计划）时才直接沿用；
            // 若它属于上游计划（如战略部下发给职能部门的 STRAT_TO_FUNC 计划），
            // 必须在下游计划中另建任务行，否则下游计划会因"不存在基础性任务"而无法下发。
            boolean requestedTaskBelongsToDownstreamPlan = isDownstreamPlanFor(requestedPlan, ownerOrg, targetOrg);
            if (requestedTaskBelongsToDownstreamPlan
                    && PlanStatus.fromRaw(requestedPlan.getStatus()) != PlanStatus.DISTRIBUTED) {
                return requestedTaskId;
            }
        }

        StrategicTask parentTask = null;
        if (parent != null && parent.getTaskId() != null) {
            parentTask = taskRepository.findById(parent.getTaskId()).orElse(null);
        }

        Long cycleId = firstNonNull(
                requestedCycleId,
                requestedTask != null ? requestedTask.getCycleId() : null,
                parentTask != null ? parentTask.getCycleId() : null
        );
        if (cycleId == null) {
            throw new IllegalArgumentException("周期ID不能为空");
        }
        // 2026-11-05（MA4）：归属/目标组织的空值校验已前移到方法入口（守卫之前），此处不再重复。

        Plan downstreamPlan = findOrCreatePlan(
                cycleId,
                PlanLevel.FUNC_TO_COLLEGE,
                ownerOrg.getId(),
                targetOrg.getId()
        );
        if (PlanStatus.fromRaw(downstreamPlan.getStatus()) == PlanStatus.DISTRIBUTED) {
            // 2026-10-23 用户定案：学院子指标也允许在已下发计划上新增，与职能部门侧口径一致——
            // 新增先落库（DRAFT），由调用方把新指标计入「待发起异动」，
            // 点「发起异动」后统一走 PLAN_MUTATION_STRATEGY 审批，通过才正式生效。
            // 原「下发动作」拦截保留在导入/重复下发路径（见 ensurePlanNotDistributedForTask），
            // 这里只是「新增一条子指标」，不再直接拒绝。
            log.info("[Strategy] 向已下发学院计划新增子指标，转入异动审批口径: planId={}, ownerOrgId={}, targetOrgId={}",
                    downstreamPlan.getId(), ownerOrg.getId(), targetOrg.getId());
        }

        String taskName = firstNonBlank(
                parentTask != null ? parentTask.getName() : null,
                requestedTask != null ? requestedTask.getName() : null,
                "学院子指标"
        );
        TaskType taskType = parentTask != null && parentTask.getTaskType() != null
                ? parentTask.getTaskType()
                : requestedTask != null && requestedTask.getTaskType() != null
                    ? requestedTask.getTaskType()
                    : TaskType.DEVELOPMENT;

        return findOrCreateTask(downstreamPlan, cycleId, taskName, taskType, targetOrg, ownerOrg).getId();
    }

    /**
     * 判断给定计划是否为本次"职能部门下发学院"的目标下游计划：
     * 计划级别为 FUNC_TO_COLLEGE，且创建组织=当前职能部门、目标组织=目标学院。
     */
    private boolean isDownstreamPlanFor(Plan plan, SysOrg ownerOrg, SysOrg targetOrg) {
        if (plan == null || plan.getPlanLevel() != PlanLevel.FUNC_TO_COLLEGE) {
            return false;
        }
        boolean sameOwner = ownerOrg != null
                && Objects.equals(plan.getCreatedByOrgId(), ownerOrg.getId());
        boolean sameTarget = targetOrg != null
                && Objects.equals(plan.getTargetOrgId(), targetOrg.getId());
        return sameOwner && sameTarget;
    }

    private Plan findOrCreatePlan(Long cycleId, PlanLevel planLevel, Long createdByOrgId, Long targetOrgId) {
        return planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                        cycleId,
                        planLevel,
                        createdByOrgId,
                        targetOrgId
                )
                .orElseGet(() -> planRepository.save(Plan.create(cycleId, targetOrgId, createdByOrgId, planLevel)));
    }

    private StrategicTask findOrCreateTask(
            Plan plan,
            Long cycleId,
            String taskName,
            TaskType taskType,
            SysOrg targetOrg,
            SysOrg createdByOrg) {
        return taskRepository.findByPlanIdAndCycleId(plan.getId(), cycleId).stream()
                .filter(task -> normalizeKey(task.getName()).equals(normalizeKey(taskName)))
                .filter(task -> task.getTaskType() == taskType)
                .findFirst()
                .orElseGet(() -> taskRepository.save(
                        StrategicTask.create(taskName, taskType, plan.getId(), cycleId, targetOrg, createdByOrg)
                ));
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

    /**
     * 2026-10-24 用户定案：计划审批中（PENDING，下发审批在负责人/校领导环节）内容冻结——
     * 新增指标被拦下。审批通过后计划转 DISTRIBUTED，新增走异动审批链（既有定案不受影响）；
     * 驳回/撤回后计划回到可编辑状态，新增随即放开。
     */
    private void assertPlanNotUnderApprovalForTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        Long planId = taskRepository.findById(taskId)
                .map(StrategicTask::getPlanId)
                .orElse(null);
        if (planId == null) {
            return;
        }
        String planStatus = planRepository.findById(planId)
                .map(Plan::getStatus)
                .orElse(null);
        if ("PENDING".equalsIgnoreCase(String.valueOf(planStatus))) {
            throw new IllegalStateException(
                    "计划正在审批中，暂不能新增指标：请等审批结束（通过或退回）后再操作，或先撤回本次审批");
        }
    }

    private Long firstNonNull(Long... values) {
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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
