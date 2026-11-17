package com.sism.strategy.application;

import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.SysOrg;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.strategy.domain.cycle.Cycle;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.CycleRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlanIntegrityService {

    private static final long ENSURE_INTERVAL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final Object STRAT_TO_FUNC_LOCK = new Object();

    private final PlanRepository planRepository;
    private final CycleRepository cycleRepository;
    private final OrganizationRepository organizationRepository;
    private final StrategyOrgProperties strategyOrgProperties;
    private final AtomicBoolean ensureInProgress = new AtomicBoolean(false);
    private volatile long lastEnsuredAtMillis = -1L;

    @Transactional
    public void ensurePlanMatrix() {
        long now = System.currentTimeMillis();
        if (isRecentlyEnsured(now)) {
            return;
        }

        if (!ensureInProgress.compareAndSet(false, true)) {
            log.debug("[PlanIntegrityService] Skip ensurePlanMatrix because another execution is in progress");
            return;
        }

        List<Cycle> cycles = cycleRepository.findAll();
        try {
            now = System.currentTimeMillis();
            if (isRecentlyEnsured(now)) {
                return;
            }

            List<SysOrg> organizations = organizationRepository.findAll().stream()
                    .filter(org -> Boolean.TRUE.equals(org.getIsActive()))
                    .filter(org -> !Boolean.TRUE.equals(org.getIsDeleted()))
                    .toList();

            List<SysOrg> functionalOrgs = organizations.stream()
                    .filter(org -> OrgType.functional.equals(org.getType()))
                    .toList();

            for (Cycle cycle : cycles) {
                ensureStrategyToFunctionalPlans(cycle, functionalOrgs);
                // 2026-11-17（学院线粒度改造）：学院线不再自愈，按需懒创建（见 ensureFunctionalToCollegePlans 注释）。
            }

            markEnsureCompletedAfterCommit();
            log.info(
                    "[PlanIntegrityService] Ensured plan matrix for cycles={}, functionalOrgs={}, academicOrgs={}",
                    cycles.size(),
                    functionalOrgs.size(),
                    // 2026-11-17（学院线粒度改造）：学院线不再自愈（按需懒创建），academicOrgs 不再参与
                    academicOrgsCount(organizations)
            );
        } finally {
            ensureInProgress.set(false);
        }
    }

    /** 学院组织数量（仅用于日志；2026-11-17 起学院线不再自愈创建）。 */
    private int academicOrgsCount(List<SysOrg> organizations) {
        return (int) organizations.stream()
                .filter(org -> OrgType.academic.equals(org.getType()))
                .count();
    }

    private boolean isRecentlyEnsured(long nowMillis) {        return lastEnsuredAtMillis > 0
                && nowMillis - lastEnsuredAtMillis < ENSURE_INTERVAL_MILLIS;
    }

    private void markEnsureCompletedAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lastEnsuredAtMillis = System.currentTimeMillis();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lastEnsuredAtMillis = System.currentTimeMillis();
            }
        });
    }

    private void ensureStrategyToFunctionalPlans(Cycle cycle, List<SysOrg> functionalOrgs) {
        synchronized (STRAT_TO_FUNC_LOCK) {
            for (SysOrg functionalOrg : functionalOrgs) {
                ensurePlanExists(
                        cycle.getId(),
                        PlanLevel.STRAT_TO_FUNC,
                        strategyOrgProperties.getSystemAdminOrgId(),
                        functionalOrg.getId()
                );
            }
        }
    }

    /**
     * 2026-11-05（学院指标直发改造）：学院下发计划的创建组织改为战略发展部。
     *
     * <p>原口径是「职能部门 × 学院」的笛卡尔积——那正是旧链路（职能部门向学院分发）的计划容器。
     * 链路取消后这批计划不再有内容，且会让「按创建组织找计划」的查询落到错误的计划上
     * （例如学院下发查不到自己那条计划）。改为「战略部 × 学院」，与导入/下发口径一致。
     *
     * <p>存量 FUNC_TO_COLLEGE 计划不做数据迁移，保留为只读历史。
     *
     * <p><b>2026-11-17（学院线粒度改造）：本方法已停用——学院线不再由矩阵自愈创建。</b>
     *
     * <p>原因：学院下发计划的「线身份」现在包含归口部门
     * （= (cycle, FUNC_TO_COLLEGE, 战略发展部, 学院, 归口部门)），
     * 而自愈按「战略部 × 学院」笛卡尔积造出来的行<b>没有归口维度</b>：
     * <ul>
     *   <li>它不携带线身份 → 在新唯一索引下与真实线抢同一个查询位
     *       （导入取计划时命中它 → 「该线已下发」误判 / 同名子指标被重复创建）；</li>
     *   <li>它是 V105 迁移要归档的对象（空壳行）——继续造就等于造完再归档，来回打架；</li>
     *   <li>一个学院可能有多条归口不同的线，自愈无从知道该造哪几条。</li>
     * </ul>
     *
     * <p>改为<b>按需懒创建</b>：线由导入链（{@code BusinessImportApplicationService}，
     * 建线时带上归口部门）与异动链在真正要用到时创建。
     * 若后续确实需要自愈，只能按「该学院已有子指标父级核心指标的去重归口集」造线，
     * 且必须带 functional_org_id——不允许再造 (35, 学院, NULL) 空壳。
     */
    private void ensureFunctionalToCollegePlans(Cycle cycle, List<SysOrg> academicOrgs) {
        // 2026-11-17：学院线按需懒创建，本方法保留为空实现（留痕：口径变更的落点与原因）。
    }

    private void ensurePlanExists(Long cycleId, PlanLevel planLevel, Long createdByOrgId, Long targetOrgId) {
        List<Plan> activePlans = planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                cycleId,
                planLevel,
                createdByOrgId,
                targetOrgId
        );

        if (activePlans.size() > 1) {
            log.error(
                    "[PlanIntegrityService] Duplicate active plans detected: planIds={}, cycleId={}, planLevel={}, createdByOrgId={}, targetOrgId={}",
                    activePlans.stream().map(Plan::getId).toList(),
                    cycleId,
                    planLevel,
                    createdByOrgId,
                    targetOrgId
            );
        }

        if (!activePlans.isEmpty()) {
            return;
        }

        Plan createdPlan = Plan.create(cycleId, targetOrgId, createdByOrgId, planLevel);
        try {
            planRepository.saveAndFlush(createdPlan);
            log.info(
                    "[PlanIntegrityService] Auto-created missing plan: cycleId={}, planLevel={}, createdByOrgId={}, targetOrgId={}",
                    cycleId,
                    planLevel,
                    createdByOrgId,
                    targetOrgId
            );
        } catch (DataIntegrityViolationException ex) {
            List<Plan> concurrentPlans = planRepository.findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                    cycleId,
                    planLevel,
                    createdByOrgId,
                    targetOrgId
            );
            if (!concurrentPlans.isEmpty()) {
                log.info(
                        "[PlanIntegrityService] Detected concurrent plan creation, reusing existing active plan: planId={}, cycleId={}, planLevel={}, createdByOrgId={}, targetOrgId={}",
                        concurrentPlans.get(0).getId(),
                        cycleId,
                        planLevel,
                        createdByOrgId,
                        targetOrgId
                );
                return;
            }
            throw ex;
        }
    }
}
