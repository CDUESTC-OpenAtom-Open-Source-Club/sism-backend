-- =====================================================
-- 2026-10-26 权限模型改造 P0：角色作用域（角色 + 部门）
--
-- 原则：权限 = 角色 + 该角色对应的部门；唯一事实源是数据库表。
-- 复用 sys_role.data_access_mode（原有 ALL/OWN_ORG），扩展 FIXED_ORG，
-- 并新增 scope_org_id 表示固定作用域组织。
--
-- 作用域语义：
--   ALL       → 全组织有效（系统管理员）
--   OWN_ORG   → 角色在用户所属组织有效（填报人/审核人/分管校领导）
--   FIXED_ORG → 仅当 scope_org_id = 用户 org_id 时有效（战略发展部负责人固定 35）
--
-- 说明：
--   - 该迁移是纯数据/结构迁移，幂等，可在历史库上安全执行（V99 > V98，顺序执行）。
--   - 视图 v_user_effective_permission 用于人工核对与差异报告。
-- =====================================================

ALTER TABLE public.sys_role
    ADD COLUMN IF NOT EXISTS scope_org_id BIGINT NULL;

COMMENT ON COLUMN public.sys_role.scope_org_id IS
    '作用域组织：仅 data_access_mode=FIXED_ORG 时生效，表示该角色只在此组织有效';

-- 放宽原 CHECK 约束，加入 FIXED_ORG
ALTER TABLE public.sys_role DROP CONSTRAINT IF EXISTS sys_role_data_access_mode_check;
ALTER TABLE public.sys_role ADD CONSTRAINT sys_role_data_access_mode_check
    CHECK (data_access_mode IN ('ALL', 'OWN_ORG', 'FIXED_ORG'));

-- 按角色回填作用域（战略部组织 id 用组织类型定位，避免硬编码 35）
UPDATE public.sys_role
SET data_access_mode = 'OWN_ORG', scope_org_id = NULL
WHERE id IN (1, 2);

UPDATE public.sys_role
SET data_access_mode = 'FIXED_ORG',
    scope_org_id = (SELECT o.id FROM public.sys_org o WHERE o.type = 'admin' ORDER BY o.id LIMIT 1)
WHERE id = 3;

UPDATE public.sys_role
SET data_access_mode = 'OWN_ORG', scope_org_id = NULL
WHERE id = 4;

UPDATE public.sys_role
SET data_access_mode = 'ALL', scope_org_id = NULL
WHERE id = 5;

-- 核对视图：任意账号的每个候选权限码及其来源（角色/组织/作用域是否有效）
CREATE OR REPLACE VIEW public.v_user_effective_permission AS
SELECT
    u.id                                        AS user_id,
    u.username                                  AS username,
    u.org_id                                    AS user_org_id,
    r.role_code                                  AS role_code,
    r.data_access_mode                           AS data_access_mode,
    r.scope_org_id                               AS scope_org_id,
    (r.data_access_mode = 'ALL'
        OR r.data_access_mode = 'OWN_ORG'
        OR (r.data_access_mode = 'FIXED_ORG' AND r.scope_org_id = u.org_id)) AS scope_valid,
    p.perm_code                                  AS perm_code
FROM public.sys_user u
JOIN public.sys_user_role ur ON ur.user_id = u.id
JOIN public.sys_role r ON r.id = ur.role_id
LEFT JOIN public.sys_role_permission rp ON rp.role_id = r.id
LEFT JOIN public.sys_permission p ON p.id = rp.perm_id;
