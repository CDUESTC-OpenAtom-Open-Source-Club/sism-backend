-- =====================================================
-- 2026-10-25 用户定案：二级学院上报链调整为「确认过的五节点」+ 命名统一
--
-- 确认流程（#82 复盘后口径）：
--   学院填报人 → 学院院长（审核人） → 分管职能部门负责人 → 分管职能部门校领导
--   → 战略发展部审核人
--
-- 与现状（V96 恢复后的 5 节点）的差异：
--   ① 学院侧由两级（二级学院审批人 audit1 → 学院院长 leader）合并为一级
--     「学院审核人」（院长账号，role 4）——学院只配了填报人+领导两级账号；
--   ② 职能部门侧拆为两级：分管职能部门负责人（role 2 审核人）
--     → 分管职能部门校领导（role 4 领导）；
--   ③ 命名统一：院长不是校领导，学院侧环节统一叫「审核人」；
--     战略部终态节点「终审人」同步改「审核人」（与职能部门链命名对齐）。
--
-- 在途实例安全性：
--   * 停在旧步骤 2（二级学院审批人 audit1）的实例：审批人已按旧规则解析锁定，
--     audit1 通过后 createNextStep 按 step_order=3 找到新定义（分管职能部门负责人）
--     正常续转——学院院长环节由新定义在后续实例生效；
--   * 停在旧步骤 3/4 的实例：approver 已锁定，照常续转，仅步骤显示名更新。
-- =====================================================

-- ① 原步骤 2（二级学院审批人 audit1）→ 学院审核人（院长 leader）审批
UPDATE public.audit_step_def
SET step_name = '学院审核人审批',
    role_id = 4,
    updated_at = NOW()
WHERE id = 12 AND flow_id = 4;

-- ② 原步骤 3（学院院长 leader）→ 分管职能部门负责人（审核人 audit1）审批
UPDATE public.audit_step_def
SET step_name = '分管职能部门负责人审批',
    role_id = 2,
    updated_at = NOW()
WHERE id = 13 AND flow_id = 4;

-- ③ 原步骤 4（职能部门审核人 audit1）→ 分管职能部门校领导（leader）审批
UPDATE public.audit_step_def
SET step_name = '分管职能部门校领导审批',
    role_id = 4,
    updated_at = NOW()
WHERE id = 14 AND flow_id = 4;

-- ④ 原步骤 5（战略发展部终审人）→ 战略发展部审核人审批（命名对齐，审批席不变）
UPDATE public.audit_step_def
SET step_name = '战略发展部审核人审批',
    updated_at = NOW()
WHERE id = 15 AND flow_id = 4;

-- ⑤ 职能部门上报链（flow 3）末环命名同步对齐
UPDATE public.audit_step_def
SET step_name = '战略发展部审核人审批',
    updated_at = NOW()
WHERE id = 10 AND flow_id = 3;
