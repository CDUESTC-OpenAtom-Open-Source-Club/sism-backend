-- plan clean seed
-- Business rule:
-- - plan is a distribution container, not a self-owned annual container.
-- - Seed both baseline matrices:
--   1) 系统管理员部门(战略发展部) -> 各职能部门
--   2) 战略发展部 -> 各二级学院
--   2026-11-05 学院指标直发改造：原「各职能部门 -> 各二级学院」的笛卡尔积取消，
--   学院子指标统一由战略发展部下发，复用 FUNC_TO_COLLEGE 计划级别（不新增枚举）。
--   存量 FUNC_TO_COLLEGE 计划不做迁移，保留为只读历史。
-- - Runtime backend must also auto-heal missing plan rows when new organizations are added.
-- Status rule:
-- - plan.status is the authoritative package-level distribution status.
-- - indicator.status may exist as a downstream projection/lifecycle field, but when the two
--   differ, plan.status is the source-of-truth for the overall package.
-- - All clean-seed plan containers stay at the DRAFT baseline.

BEGIN;

INSERT INTO public.plan (
    id,
    cycle_id,
    created_at,
    updated_at,
    is_deleted,
    target_org_id,
    created_by_org_id,
    plan_level,
    status,
    audit_instance_id
)
SELECT
    seed.id,
    seed.cycle_id,
    NOW(),
    NOW(),
    false,
    seed.target_org_id,
    seed.created_by_org_id,
    seed.plan_level,
    'DRAFT' AS status,
    NULL::BIGINT AS audit_instance_id
FROM (
    SELECT
        c.id * 1000 + f.id AS id,
        c.id AS cycle_id,
        f.id AS target_org_id,
        35::BIGINT AS created_by_org_id,
        'STRAT_TO_FUNC'::plan_level AS plan_level
    FROM public.cycle c
    JOIN public.sys_org f
        ON f.type = 'functional'
       AND f.is_deleted = false
       AND f.is_active = true

    UNION ALL

    SELECT
        c.id * 100000 + a.id AS id,
        c.id AS cycle_id,
        a.id AS target_org_id,
        35::BIGINT AS created_by_org_id,
        'FUNC_TO_COLLEGE'::plan_level AS plan_level
    FROM public.cycle c
    JOIN public.sys_org a
        ON a.type = 'academic'
       AND a.is_deleted = false
       AND a.is_active = true
) AS seed
ON CONFLICT (id) DO UPDATE
SET
    cycle_id = EXCLUDED.cycle_id,
    updated_at = EXCLUDED.updated_at,
    is_deleted = EXCLUDED.is_deleted,
    target_org_id = EXCLUDED.target_org_id,
    created_by_org_id = EXCLUDED.created_by_org_id,
    plan_level = EXCLUDED.plan_level,
    status = EXCLUDED.status,
    audit_instance_id = EXCLUDED.audit_instance_id;

COMMIT;
