-- 幂等补齐组织、角色、权限、管理员用户及审批流程等最小基础数据。

INSERT INTO public.sys_org (
    id,
    name,
    type,
    is_active,
    sort_order,
    created_at,
    updated_at,
    parent_org_id,
    level,
    is_deleted
)
VALUES (
    35,
    '战略发展部',
    'admin',
    true,
    0,
    NOW(),
    NOW(),
    NULL,
    1,
    false
)
ON CONFLICT (id) DO NOTHING;

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
VALUES
    (1, 'ROLE_REPORTER', '填报人', 'OWN_ORG', true, '计划/任务/指标内容的实际填报人。负责草稿编辑、填报提交、月报填报，不负责审批节点决策。', NOW(), NOW()),
    (2, 'ROLE_APPROVER', '审核人', 'OWN_ORG', true, '通用审核人审批角色。用于职能部门负责人、二级学院审核人，以及职能部门对下级单位提交内容的终审节点。具体审批对象由当前 org_id 与 step_name 共同决定。2026-10-25 用户定案：二级学院只设「填报人」与「审核人」两个席位，学院不设「分管校领导」。', NOW(), NOW()),
    (3, 'ROLE_STRATEGY_DEPT_HEAD', '战略发展部负责人', 'ALL', true, '战略发展部审批角色。用于战略发展部负责人审批节点与战略发展部终审节点，固定落到战略发展部组织。', NOW(), NOW()),
    (4, 'ROLE_VICE_PRESIDENT', '分管校领导', 'ALL', true, '高级审批角色。用于分管校领导审批节点与学院院长审批节点，具体身份由组织层级与当前流程节点共同决定。', NOW(), NOW()),
    (5, 'ROLE_SYSTEM_ADMIN', '系统管理员', 'ALL', true, '系统维护与自动审批专用角色，不作为人工业务审批席位参与候选人解析。', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.sys_permission (
    id,
    perm_code,
    perm_name,
    perm_type,
    parent_id,
    route_path,
    page_key,
    action_key,
    sort_order,
    is_enabled,
    remark,
    created_at,
    updated_at
)
VALUES
    (1, 'PAGE_DASHBOARD', '数据看板', 'PAGE', NULL, '/dashboard', 'dashboard', NULL, 10, true, '各级组织数据看板入口', NOW(), NOW()),
    (2, 'PAGE_STRATEGY_TASK', '战略任务管理', 'PAGE', NULL, '/strategy/task', 'strategy_task', NULL, 20, true, '战略任务下发、审批、填报审核入口', NOW(), NOW()),
    (3, 'PAGE_DATA_REPORT', '数据填报', 'PAGE', NULL, '/data/report', 'data_report', NULL, 30, true, '职能部门/二级学院数据填报入口', NOW(), NOW()),
    (4, 'PAGE_INDICATOR_DISPATCH', '指标下发与审批', 'PAGE', NULL, '/indicator/dispatch', 'indicator_dispatch', NULL, 40, true, '指标下发、审批、填报审核入口', NOW(), NOW()),
    (5, 'BTN_STRATEGY_TASK_DISPATCH_SUBMIT', '下发（提交）', 'BUTTON', 2, NULL, NULL, 'dispatch_submit', 10, true, '战略任务下发提交', NOW(), NOW()),
    (6, 'BTN_STRATEGY_TASK_DISPATCH_APPROVE', '下发审核', 'BUTTON', 2, NULL, NULL, 'dispatch_approve', 20, true, '战略任务下发审批', NOW(), NOW()),
    (7, 'BTN_STRATEGY_TASK_REPORT_APPROVE', '填报审核', 'BUTTON', 2, NULL, NULL, 'report_approve', 30, true, '战略任务填报审核', NOW(), NOW()),
    (8, 'BTN_DATA_REPORT_SUBMIT', '提交', 'BUTTON', 3, NULL, NULL, 'submit', 10, true, '数据填报提交', NOW(), NOW()),
    (9, 'BTN_DATA_REPORT_APPROVE', '审核', 'BUTTON', 3, NULL, NULL, 'approve', 20, true, '数据填报审核', NOW(), NOW()),
    (10, 'BTN_INDICATOR_DISPATCH_SUBMIT', '下发（提交）', 'BUTTON', 4, NULL, NULL, 'dispatch_submit', 10, true, '指标下发提交', NOW(), NOW()),
    (11, 'BTN_INDICATOR_DISPATCH_APPROVE', '下发审核', 'BUTTON', 4, NULL, NULL, 'dispatch_approve', 20, true, '指标下发审核', NOW(), NOW()),
    (12, 'BTN_INDICATOR_REPORT_APPROVE', '填报审核', 'BUTTON', 4, NULL, NULL, 'report_approve', 30, true, '指标填报审核', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

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
    token_version
)
VALUES (
    124,
    NOW(),
    NOW(),
    true,
    '$2b$10$D6/ksnZaAwCkuD13HaTHTOrTKNnlawU5eThyDlK7t.rZx5PrRpgLy',
    '系统管理员',
    NULL,
    'admin',
    35,
    NULL,
    0
)
ON CONFLICT (id) DO NOTHING;

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
VALUES
    (1, 'PLAN_DISPATCH_STRATEGY', 'Plan下发审批（战略发展部）', true, NOW(), NOW(), '战略发展部发起的 Plan 下发审批流程', 1, 'PLAN'),
    (2, 'PLAN_DISPATCH_FUNCDEPT', 'Plan下发审批（职能部门）', true, NOW(), NOW(), '职能部门发起的 Plan 下发审批流程', 1, 'PLAN'),
    (3, 'PLAN_APPROVAL_FUNCDEPT', 'Plan审批流程（职能部门）', true, NOW(), NOW(), '职能部门 Plan / 月报复用审批流程', 1, 'PLAN'),
    (4, 'PLAN_APPROVAL_COLLEGE', 'Plan审批流程（二级学院）', true, NOW(), NOW(), '二级学院 Plan / 月报复用审批流程', 1, 'PLAN'),
    (5, 'PLAN_MUTATION_STRATEGY', '指标异动审批（战略发展部）', true, NOW(), NOW(), '战略发展部发起的指标异动审批流程', 1, 'INDICATOR')
ON CONFLICT (id) DO NOTHING;
