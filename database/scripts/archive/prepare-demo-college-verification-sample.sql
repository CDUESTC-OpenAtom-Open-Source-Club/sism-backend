-- Prepare a valid college-approval verification sample for demo end-to-end checks.
-- This script is intended for isolated verification databases only.
--
-- Goal:
-- - Make PLAN_APPROVAL_COLLEGE for plan 403657 (source org 36 -> target org 57)
--   satisfy the "college indicators exist and sum to 100" rule.
--
-- Safe to re-run.

\set ON_ERROR_STOP on

INSERT INTO public.indicator (
    id,
    task_id,
    parent_indicator_id,
    indicator_desc,
    weight_percent,
    sort_order,
    remark,
    created_at,
    updated_at,
    type,
    progress,
    is_deleted,
    owner_org_id,
    target_org_id,
    status,
    responsible_user_id
)
VALUES
    (
        920001,
        41001,
        2001,
        '党委办公室对计算机学院下发年度协同指标 A',
        50.00,
        1,
        'demo college approval verification sample',
        NOW(),
        NOW(),
        '定量',
        0,
        false,
        36,
        57,
        'DISTRIBUTED',
        423
    ),
    (
        920002,
        41001,
        2002,
        '党委办公室对计算机学院下发年度协同指标 B',
        50.00,
        2,
        'demo college approval verification sample',
        NOW(),
        NOW(),
        '定性',
        0,
        false,
        36,
        57,
        'DISTRIBUTED',
        423
    )
ON CONFLICT (id) DO UPDATE
SET
    task_id = EXCLUDED.task_id,
    parent_indicator_id = EXCLUDED.parent_indicator_id,
    indicator_desc = EXCLUDED.indicator_desc,
    weight_percent = EXCLUDED.weight_percent,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = EXCLUDED.updated_at,
    type = EXCLUDED.type,
    progress = EXCLUDED.progress,
    is_deleted = EXCLUDED.is_deleted,
    owner_org_id = EXCLUDED.owner_org_id,
    target_org_id = EXCLUDED.target_org_id,
    status = EXCLUDED.status,
    responsible_user_id = EXCLUDED.responsible_user_id;
