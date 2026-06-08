-- Split the technical system administrator account from the real
-- strategic vice-president workflow seat.

INSERT INTO public.sys_role (
    id,
    role_code,
    role_name,
    data_access_mode,
    is_enabled,
    remark,
    created_at,
    updated_at
)
VALUES (
    5,
    'ROLE_SYSTEM_ADMIN',
    '系统管理员',
    'ALL',
    true,
    '系统维护与自动审批专用角色，不作为人工业务审批席位参与候选人解析。',
    NOW(),
    NOW()
)
ON CONFLICT (id) DO UPDATE
SET
    role_code = EXCLUDED.role_code,
    role_name = EXCLUDED.role_name,
    data_access_mode = EXCLUDED.data_access_mode,
    is_enabled = EXCLUDED.is_enabled,
    remark = EXCLUDED.remark,
    updated_at = EXCLUDED.updated_at;

UPDATE public.sys_role
SET
    role_name = '系统管理员',
    data_access_mode = 'ALL',
    is_enabled = true,
    remark = '系统维护与自动审批专用角色，不作为人工业务审批席位参与候选人解析。',
    updated_at = NOW()
WHERE role_code = 'ROLE_SYSTEM_ADMIN';

INSERT INTO public.sys_user (
    id,
    created_at,
    updated_at,
    is_active,
    password_hash,
    real_name,
    sso_id,
    username,
    org_id,
    avatar_url,
    token_version,
    is_demo
)
VALUES (
    186,
    NOW(),
    NOW(),
    true,
    '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.',
    '战略发展部分管校领导1',
    NULL,
    'zlb_vp1',
    35,
    NULL,
    0,
    false
)
ON CONFLICT (id) DO UPDATE
SET
    updated_at = EXCLUDED.updated_at,
    is_active = EXCLUDED.is_active,
    password_hash = EXCLUDED.password_hash,
    real_name = EXCLUDED.real_name,
    sso_id = EXCLUDED.sso_id,
    username = EXCLUDED.username,
    org_id = EXCLUDED.org_id,
    avatar_url = EXCLUDED.avatar_url,
    token_version = EXCLUDED.token_version,
    is_demo = EXCLUDED.is_demo;

DELETE FROM public.sys_user_role
WHERE user_id = 124
  AND role_id = 4;

INSERT INTO public.sys_user_role (user_id, role_id, created_at)
SELECT 124, r.id, NOW()
FROM public.sys_role r
WHERE r.role_code = 'ROLE_SYSTEM_ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO public.sys_user_role (user_id, role_id, created_at)
VALUES (186, 4, NOW())
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO public.sys_role_permission (role_id, perm_id, created_at)
SELECT r.id, p.id, NOW()
FROM public.sys_role r
CROSS JOIN public.sys_permission p
WHERE r.role_code = 'ROLE_SYSTEM_ADMIN'
ON CONFLICT (role_id, perm_id) DO NOTHING;

SELECT setval('public.sys_role_id_seq', GREATEST((SELECT MAX(id) FROM public.sys_role), 1), true);
SELECT setval('public.sys_user_user_id_seq', GREATEST((SELECT MAX(id) FROM public.sys_user), 1), true);
