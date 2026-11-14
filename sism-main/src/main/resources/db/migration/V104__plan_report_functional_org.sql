-- 2026-11-13（学院上报按归口部门分批提交，用户拍板方案A：indicator 不动，父级反查 100% 可靠）
--
-- 业务口径：
--   学院子指标统一由战略发展部创建（indicator.owner_org_id=35），每个学院子指标挂在某父级
--   核心指标（parent_indicator_id）下，父级的 target_org_id 就是「归口职能部门」。
--   学院上报按归口部门分批提交：学院 × 归口部门 = 一份月报 + 一条审批链；
--   职能部门只看提交给本部门的上报；上报/异动互斥锁只锁本部门。
--
-- 本迁移做两件事：
--   ① plan_report 加可空列 functional_org_id：报告行自带归口，席位解析/互斥判定优先读它；
--      为 null 时回退 plan.functional_org_id / 旧口径（向后兼容，存量行为不变）。
--   ② 存量回填 plan.functional_org_id：对「战略部创建（created_by_org_id=35）、FUNC_TO_COLLEGE、
--      functional_org_id 为空」的计划，按「计划下学院子指标 → 父级核心指标.target_org_id」取 MIN 回填。
--      与导入链（BusinessImportApplicationService#applyFunctionalOrgFromDispatchChain）、
--      计划提交补写（PlanApplicationService#submitPlanForApprovalInTransaction，经
--      PlanFunctionalOrgResolver）共用同一口径，避免漂移。
--
-- 不加外键/唯一约束：与本表既有列一致（均无 FK）；「同月同组织多部门并存」是新口径的合法形态，
-- (plan, report_org, month, functional_org_id) 的唯一性由应用层判定保证
-- （functional_org_id 为 NULL 时 NULL 与 NULL 同组、NULL 与非 NULL 不同组，IS NOT DISTINCT FROM 语义）。
-- plan_report 当前 0 行，加列零回填成本。

ALTER TABLE public.plan_report
    ADD COLUMN IF NOT EXISTS functional_org_id BIGINT;

COMMENT ON COLUMN public.plan_report.functional_org_id IS
    '2026-11-13 学院上报按归口部门分批提交：本报告归属的职能部门（归口）。'
    '权威来源=提交时按「报告明细指标 → 父级核心指标.target_org_id」反查落列；'
    '为空时保持旧口径（互斥全锁、席位回退 plan.functional_org_id）';

-- 存量回填：纯内联常量 DML（无外部输入、无拼接），join 口径与
-- PlanFunctionalOrgResolver.resolveFunctionalOrgIds 完全一致（同款子查询）。
UPDATE public.plan p
SET functional_org_id = backfill.min_target_org
FROM (
    SELECT t.plan_id AS plan_id, MIN(parent.target_org_id) AS min_target_org
    FROM public.sys_task t
    JOIN public.indicator child ON child.task_id = t.task_id
         AND child.parent_indicator_id IS NOT NULL
         AND COALESCE(child.is_deleted, false) = false
    JOIN public.indicator parent ON parent.id = child.parent_indicator_id
    WHERE parent.target_org_id IS NOT NULL
    GROUP BY t.plan_id
) backfill
WHERE p.id = backfill.plan_id
  AND p.plan_level = 'FUNC_TO_COLLEGE'
  AND p.created_by_org_id = 35
  AND p.functional_org_id IS NULL;

COMMENT ON COLUMN public.plan.functional_org_id IS
    '2026-11-08 学院指标单层口径：该学院指标的归口职能部门。仅 FUNC_TO_COLLEGE 计划使用；'
    '2026-11-13 起存量按「学院子指标 → 父级核心指标.target_org_id 取 MIN」回填（V104），'
    '为空时学院上报链的审批席位回退到 created_by_org_id（改造前口径）';
