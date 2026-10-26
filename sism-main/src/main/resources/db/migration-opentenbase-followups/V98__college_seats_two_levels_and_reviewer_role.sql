-- =====================================================
-- 2026-10-26 用户定案（承接《SISM-审批链验证报告-2026-10-26》核对结论）：
-- 二级学院只设两级席位——填报人 + 审核人；学院侧不存在「分管校领导」。
--
-- 学院上报链（PLAN_APPROVAL_COLLEGE，flow_id=4）定为 5 节点：
--   1 填报人提交                    （发起人本人）
--   2 学院审核人审批                role 2 审核人（学院本级席位）
--   3 分管职能部门负责人审批        role 2 审核人（职能部门本级席位）
--   4 分管职能部门校领导审批        role 4 分管校领导（职能部门本级席位）
--   5 战略发展部审核人审批          role 3 战略发展部负责人（终审）
--
-- 与 V97 的差异：V97 把第 2 环设成 role 4（学院领导/院长），与「学院不设分管校领导」
-- 冲突；V98 改回 role 2，使第 2 环由学院的「审核人」席位承担。
-- 席位归属（谁审批）由 ApproverResolver 按 flow + 步骤名解析：
--   第 2 环解析到「学院本级」（requesterOrgId = 学院）
--   第 3/4 环解析到「职能部门本级」（sourceOrgId = 下发该学院的职能部门）
--   第 5 环解析到战略发展部（org35）
--
-- 在途实例安全性：
--   已生成的步骤实例 approver_id 已锁定，链定义变更不影响其后续流转；
--   未走到的后续步骤按新定义解析。
-- =====================================================

-- ① 第 2 环：学院审核人审批（role 2 审核人，取代 V97 的 role 4 学院领导）
UPDATE public.audit_step_def
SET step_name = '学院审核人审批',
    role_id = 2,
    updated_at = NOW()
WHERE id = 12 AND flow_id = 4;

-- ② 第 3 环：分管职能部门负责人审批（role 2 审核人，职能部门本级）
UPDATE public.audit_step_def
SET step_name = '分管职能部门负责人审批',
    role_id = 2,
    updated_at = NOW()
WHERE id = 13 AND flow_id = 4;

-- ③ 第 4 环：分管职能部门校领导审批（role 4 分管校领导，职能部门本级）
UPDATE public.audit_step_def
SET step_name = '分管职能部门校领导审批',
    role_id = 4,
    updated_at = NOW()
WHERE id = 14 AND flow_id = 4;

-- ④ 第 5 环：战略发展部审核人审批（role 3，终审）
UPDATE public.audit_step_def
SET step_name = '战略发展部审核人审批',
    updated_at = NOW()
WHERE id = 15 AND flow_id = 4;

-- ⑤ 职能部门上报链（flow 3）末环命名同步
UPDATE public.audit_step_def
SET step_name = '战略发展部审核人审批',
    updated_at = NOW()
WHERE id = 10 AND flow_id = 3;

-- ⑥ 学院侧账号席位收敛为「填报人 + 审核人」两级：
--    学院不再设「分管校领导」席位（role 4），原 leader 席位账号改为 role 2 审核人，
--    账号保留（用户名不变），仅角色绑定调整。
UPDATE public.sys_user_role ur
SET role_id = 2
WHERE ur.role_id = 4
  AND ur.user_id IN (
      SELECT u.id
      FROM public.sys_user u
      JOIN public.sys_org o ON o.id = u.org_id
      WHERE o.type = 'academic'
        AND u.username NOT LIKE '%\_demo'
  );

-- ⑦ 学院侧演示账号（*_demo）保留跨环节演示能力，但去掉 role 4（学院不设分管校领导）
--    与 sys_demo_user-data.sql 种子的绑定保持一致
DELETE FROM public.sys_user_role ur
WHERE ur.role_id = 4
  AND ur.user_id IN (
      SELECT u.id
      FROM public.sys_user u
      JOIN public.sys_org o ON o.id = u.org_id
      WHERE o.type = 'academic'
  );
