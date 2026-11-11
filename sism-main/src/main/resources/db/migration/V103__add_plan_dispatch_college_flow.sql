-- =====================================================
-- 2026-11-10（学院下发审批独立流程）：新增 PLAN_DISPATCH_COLLEGE「Plan下发审批（二级学院）」。
--
-- 背景：
--   学院指标下发（FUNC_TO_COLLEGE，created_by=战略发展部）此前复用 flow 1
--   （PLAN_DISPATCH_STRATEGY，职能部门下发链）。用户拍板：学院下发要**独立流程定义**，
--   以便审批中心能区分「职能部门下发」与「学院下发」两种下发。
--
-- 新流程与 flow 1 节点完全同构（三步）：
--   步骤1 填报人提交              SUBMIT    role NULL
--   步骤2 战略发展部负责人审批    APPROVAL  role 3（is_terminal = false）
--   步骤3 分管校领导审批          APPROVAL  role 4（is_terminal = true）
--
-- 路由口径（与代码同步改动）：
--   学院指标下发（FUNC_TO_COLLEGE）提交一律走 PLAN_DISPATCH_COLLEGE；
--   职能部门下发（STRAT_TO_FUNC）继续用 flow 1（PLAN_DISPATCH_STRATEGY），零变化。
--
-- id 策略（本仓库约定，非「随便挑一个」）：
--   audit_flow_def.id / audit_step_def.id 两列虽是 nextval 序列列（见 V1 基线的
--   audit_flow_def_id_seq / audit_step_def_id_seq），但本仓库的种子与引导迁移一律
--   **显式给 id**（V84.1 引导迁移给 1..5，clean seed 给 1..18），并靠 setval 把序列
--   拉回 MAX(id)（V54/V55 同一套兜底口径；clean seed 的 reset-and-load 脚本也统一 setval）。
--   故此处沿用显式 id：flow 取 6（1..5 已占用），step 取 19/20/21（1..18 已占用），
--   与 clean seed（audit_flow_def-data.sql / audit_step_def-data.sql）**逐字对齐**——
--   两边 id 不一致会导致「迁移建一套、种子灌另一套」的流程定义分裂。
--
-- 执行顺序：先兜底停用旧链路，再建新流程。
--   ① 停用 PLAN_DISPATCH_FUNCDEPT：V101 已在迁移里停用过它，但
--      database/seeds/audit_flow_def-data.sql 是 ON CONFLICT DO UPDATE，
--      若哪天的种子把 is_enabled 写回 true，重灌种子就会把已废链路重新启用。
--      本迁移再兜一次底，语义与 V101 一致（行不删：存量 audit_instance.flow_def_id 仍引用它）。
--   ② 建 PLAN_DISPATCH_COLLEGE（is_enabled = true）+ 三个步骤。
--
-- 幂等：两段 INSERT 均用 ON CONFLICT (id) DO UPDATE，重复执行不报错、结果一致。
-- =====================================================

-- ① 兜底：旧链路（职能部门 → 学院）保持停用
UPDATE public.audit_flow_def
SET is_enabled = false,
    updated_at = NOW()
WHERE flow_code = 'PLAN_DISPATCH_FUNCDEPT';

-- ② 新流程定义（flow id = 6）
INSERT INTO public.audit_flow_def (
    id,
    flow_code,
    flow_name,
    is_enabled,
    created_at,
    updated_at,
    description,
    version,
    entity_type
)
VALUES (
    6,
    'PLAN_DISPATCH_COLLEGE',
    'Plan下发审批（二级学院）',
    true,
    NOW(),
    NOW(),
    '学院指标下发审批流程（战略发展部 → 二级学院；与 flow 1 节点同构、定义独立）',
    1,
    'PLAN'
)
ON CONFLICT (id) DO UPDATE
SET
    flow_code = EXCLUDED.flow_code,
    flow_name = EXCLUDED.flow_name,
    is_enabled = EXCLUDED.is_enabled,
    updated_at = NOW(),
    description = EXCLUDED.description,
    version = EXCLUDED.version,
    entity_type = EXCLUDED.entity_type;

-- ② 新流程的三个步骤（step id = 19/20/21，flow_id = 6）
INSERT INTO public.audit_step_def (
    id,
    flow_id,
    step_name,
    step_type,
    role_id,
    is_terminal,
    created_at,
    updated_at,
    step_no
)
VALUES
    (19, 6, '填报人提交', 'SUBMIT', NULL, false, NOW(), NOW(), 1),
    (20, 6, '战略发展部负责人审批', 'APPROVAL', 3, false, NOW(), NOW(), 2),
    (21, 6, '分管校领导审批', 'APPROVAL', 4, true, NOW(), NOW(), 3)
ON CONFLICT (id) DO UPDATE
SET
    flow_id = EXCLUDED.flow_id,
    step_name = EXCLUDED.step_name,
    step_type = EXCLUDED.step_type,
    role_id = EXCLUDED.role_id,
    is_terminal = EXCLUDED.is_terminal,
    updated_at = NOW(),
    step_no = EXCLUDED.step_no;

-- 把两个序列拉回 MAX(id)：本迁移与 clean seed 都用显式 id，
-- 序列不推进的话，后续 Hibernate IDENTITY 插入会撞主键（V54/V55 同一套兜底口径）。
SELECT setval(
    'public.audit_flow_def_id_seq',
    COALESCE((SELECT MAX(id) FROM public.audit_flow_def), 1),
    true
);

SELECT setval(
    'public.audit_step_def_id_seq',
    COALESCE((SELECT MAX(id) FROM public.audit_step_def), 1),
    true
);
