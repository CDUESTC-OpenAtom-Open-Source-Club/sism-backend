-- =====================================================
-- 2026-10-24 晚间定案：二级学院上报链恢复 5 节点
-- - 当日早间 V95 曾将链砍至 4 节点（职能部门审批人收口），经用户复核恢复：
--   填报人提交 → 二级学院审批人 → 学院院长审批人 → 职能部门审批人 → 战略发展部终审人（终）
-- - 处理两件事：
--   ① 恢复 step_def 15（战略发展部终审人，flow 4，终态）
--   ② flow 4 第 4 步（职能部门审批人）取消终态
-- 在途实例安全性：
--   * V95 期间已 APPROVED 的历史链保持原样（已归档为历史卡，不改写）；
--   * 停在第 4 步的实例通过后 createNextStep 按 step_order 找到恢复的 15 号定义 → 正常进入终审。
-- =====================================================

UPDATE public.audit_step_def
SET is_terminal = false, updated_at = NOW()
WHERE id = 14 AND flow_id = 4;

INSERT INTO public.audit_step_def (
    id, flow_id, step_name, step_type, role_id, is_terminal, created_at, updated_at, step_no
)
VALUES
    (15, 4, '战略发展部终审人审批', 'APPROVAL', 3, true, NOW(), NOW(), 5)
ON CONFLICT (id) DO UPDATE
SET
    flow_id = EXCLUDED.flow_id,
    step_name = EXCLUDED.step_name,
    step_type = EXCLUDED.step_type,
    role_id = EXCLUDED.role_id,
    is_terminal = EXCLUDED.is_terminal,
    updated_at = EXCLUDED.updated_at,
    step_no = EXCLUDED.step_no;
