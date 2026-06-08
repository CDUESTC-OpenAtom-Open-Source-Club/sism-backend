-- sys_role clean seed
-- Source rule:
-- - Align to the current plan-centric workflow model.
-- - Keep four canonical business roles plus one technical system role:
--   reporter / department head / strategy dept head / vice president.
--   system admin is reserved for maintenance and automatic workflow actions.
-- - Workflow seat names such as college dean or strategy final approver are resolved
--   from workflow step + organization scope, not from extra role rows.

BEGIN;

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
    (
        1,
        'ROLE_REPORTER',
        '填报人',
        'OWN_ORG',
        true,
        '计划/任务/指标内容的实际填报人。负责草稿编辑、填报提交、月报填报，不负责审批节点决策。',
        NOW(),
        NOW()
    ),
    (
        2,
        'ROLE_APPROVER',
        '部门负责人',
        'OWN_ORG',
        true,
        '通用部门负责人审批角色。用于职能部门负责人、二级学院负责人，以及职能部门对下级单位提交内容的终审节点。具体审批对象由当前 org_id 与 step_name 共同决定。',
        NOW(),
        NOW()
    ),
    (
        3,
        'ROLE_STRATEGY_DEPT_HEAD',
        '战略发展部负责人',
        'ALL',
        true,
        '战略发展部审批角色。用于战略发展部负责人审批节点与战略发展部终审节点，固定落到战略发展部组织。',
        NOW(),
        NOW()
    ),
    (
        4,
        'ROLE_VICE_PRESIDENT',
        '分管校领导',
        'ALL',
        true,
        '高级审批角色。用于分管校领导审批节点与学院院长审批节点，具体身份由组织层级与当前流程节点共同决定。',
        NOW(),
        NOW()
    ),
    (
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

DELETE FROM public.sys_role
WHERE id NOT IN (1, 2, 3, 4, 5);

COMMIT;
