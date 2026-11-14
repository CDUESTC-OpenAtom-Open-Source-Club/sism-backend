-- =====================================================
-- 2026-11-05 学院指标直发改造：「职能部门 → 学院」的分发链路整体取消，
-- 学院子指标统一由战略发展部（org 35）直接下发，复用 PLAN_DISPATCH_STRATEGY（flow 1）。
--
-- 本迁移只停用旧流程定义（is_enabled = false），不删行、不删 audit_step_def：
-- - 删步骤行会让历史审批卡片失去节点信息（已办/待办的步骤名取不到）；
-- - 删流程行会让存量 audit_instance.flow_def_id 变成悬空引用。
-- 存量数据保留为只读历史，仅禁止再次发起。
-- =====================================================

UPDATE public.audit_flow_def
SET is_enabled = false,
    updated_at = NOW()
WHERE flow_code = 'PLAN_DISPATCH_FUNCDEPT';
