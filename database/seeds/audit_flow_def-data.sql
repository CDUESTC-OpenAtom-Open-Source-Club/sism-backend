-- audit_flow_def clean seed
-- Scope:
-- - Only keep the four workflow templates used by the current approval chain.
-- - Exclude historical/dirty fields and rebuild clean business descriptions.
-- Naming rule:
-- - flow_code is aligned to PLAN semantics as well.
-- - Older INDICATOR_* naming is treated as historical residue and is intentionally removed here.

BEGIN;

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
ON CONFLICT (id) DO UPDATE
SET
    flow_code = EXCLUDED.flow_code,
    flow_name = EXCLUDED.flow_name,
    is_enabled = EXCLUDED.is_enabled,
    updated_at = EXCLUDED.updated_at,
    description = EXCLUDED.description,
    version = EXCLUDED.version,
    entity_type = EXCLUDED.entity_type;

COMMIT;
