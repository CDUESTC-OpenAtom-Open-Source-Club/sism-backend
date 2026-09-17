-- audit_step_def clean seed
-- Scope:
-- - Only keep the 4 approved workflow templates and their canonical 18 steps.
-- - Single-table seed only. Upstream dependencies are seeded separately by:
--   - audit_flow_def-data.sql
--   - sys_role-data.sql
-- Approver resolution note:
-- - Keep only four business role ids.
-- - Seat names are resolved from workflow step + organization scope.
-- - For example:
--   - "战略发展部负责人审批" => role 3 + org 35
--   - "分管校领导审批" => role 4 + org 35
--   - "学院院长审批人审批" => role 4 + current college org
--   - "战略发展部终审人审批" => role 3 + org 35
--   - "职能部门审批人审批" => role 2 + current functional org
-- College chain (flow 4) is 5 nodes per 2026-09-17 定案：
--   填报人提交 → 二级学院审批人 → 学院院长审批人 → 职能部门审批人 → 战略发展部终审人
--   （学院比职能部门多一级；职能部门链 flow 3 保持 4 节点不变）

BEGIN;

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
    (1, 1, '填报人提交', 'SUBMIT', NULL, false, NOW(), NOW(), 1),
    (2, 1, '战略发展部负责人审批', 'APPROVAL', 3, false, NOW(), NOW(), 2),
    (3, 1, '分管校领导审批', 'APPROVAL', 4, true, NOW(), NOW(), 3),

    (4, 2, '填报人提交', 'SUBMIT', NULL, false, NOW(), NOW(), 1),
    (5, 2, '职能部门审批人审批', 'APPROVAL', 2, false, NOW(), NOW(), 2),
    (6, 2, '分管校领导审批', 'APPROVAL', 4, true, NOW(), NOW(), 3),

    (7, 3, '填报人提交', 'SUBMIT', NULL, false, NOW(), NOW(), 1),
    (8, 3, '职能部门审批人审批', 'APPROVAL', 2, false, NOW(), NOW(), 2),
    (9, 3, '分管校领导审批', 'APPROVAL', 4, false, NOW(), NOW(), 3),
    (10, 3, '战略发展部终审人审批', 'APPROVAL', 3, true, NOW(), NOW(), 4),

    (11, 4, '填报人提交', 'SUBMIT', NULL, false, NOW(), NOW(), 1),
    (12, 4, '二级学院审批人审批', 'APPROVAL', 2, false, NOW(), NOW(), 2),
    (13, 4, '学院院长审批人审批', 'APPROVAL', 4, false, NOW(), NOW(), 3),
    (14, 4, '职能部门审批人审批', 'APPROVAL', 2, false, NOW(), NOW(), 4),
    (15, 4, '战略发展部终审人审批', 'APPROVAL', 3, true, NOW(), NOW(), 5),

    (16, 5, '填报人修改', 'SUBMIT', NULL, false, NOW(), NOW(), 1),
    (17, 5, '战略发展部负责人审批', 'APPROVAL', 3, false, NOW(), NOW(), 2),
    (18, 5, '分管校领导审批', 'APPROVAL', 4, true, NOW(), NOW(), 3)
ON CONFLICT (id) DO UPDATE
SET
    flow_id = EXCLUDED.flow_id,
    step_name = EXCLUDED.step_name,
    step_type = EXCLUDED.step_type,
    role_id = EXCLUDED.role_id,
    is_terminal = EXCLUDED.is_terminal,
    updated_at = EXCLUDED.updated_at,
    step_no = EXCLUDED.step_no;

COMMIT;
