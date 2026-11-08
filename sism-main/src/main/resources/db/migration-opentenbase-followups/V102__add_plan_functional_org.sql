-- 2026-11-08（学院指标单层口径）：学院指标的「归口职能部门」。
--
-- 为什么需要这一列：
--   学院指标改为「战略部 → 二级学院」直接下发后**没有父级**，而学院上报链
--   （PLAN_APPROVAL_COLLEGE）第 3 环「分管职能部门负责人审批」必须知道该学院归口哪个职能部门。
--   改造前这个答案藏在父级链里（父级核心指标的目标部门）；单层后父级没了，推不出来。
--   组织树也帮不上：实测 8 个学院的 parent_org_id 全部是 35，不存在「学院 → 职能部门」映射。
--   而 plan.created_by_org_id 现在固定是战略部（35），用它推席位会落到战略部，
--   与需求「职能部门审批学院上报」冲突，且会抛 No available approver candidates 导致派单失败。
--
-- 因此显式落字段：导入时由战略发展部指定归口职能部门。
--
-- 可空的原因：
--   ① 「战略部 → 职能部门」（STRAT_TO_FUNC）计划不需要它；
--   ② 存量学院计划没有该值，为 null 时审批席位回退到原口径（created_by_org_id），向后兼容。
--
-- 不加外键：与本表既有的 created_by_org_id / target_org_id 保持一致（均无 FK），
-- 避免组织机构历史清理时被外键阻塞。
ALTER TABLE public.plan
    ADD COLUMN IF NOT EXISTS functional_org_id BIGINT;

COMMENT ON COLUMN public.plan.functional_org_id IS
    '2026-11-08 学院指标单层口径：该学院指标的归口职能部门。仅 FUNC_TO_COLLEGE 计划使用；'
    '为空时学院上报链的审批席位回退到 created_by_org_id（改造前口径）';
