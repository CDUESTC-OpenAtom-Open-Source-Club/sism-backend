-- ============================================
-- SISM Clean Seed Validation
-- Purpose: validate the current clean-seed workflow chain on the modern schema
-- Scope: sys_org / sys_user / cycle / plan / sys_task / indicator
-- ============================================

\set ON_ERROR_STOP on

\echo '============================================'
\echo 'SISM Clean Seed Validation'
\echo '============================================'
\echo ''

\echo '>>> 1. Core table counts'
\echo ''

SELECT 'sys_org' AS table_name, COUNT(*) AS row_count FROM public.sys_org
UNION ALL
SELECT 'sys_user', COUNT(*) FROM public.sys_user
UNION ALL
SELECT 'sys_user_notification', COUNT(*) FROM public.sys_user_notification
UNION ALL
SELECT 'idempotency_records', COUNT(*) FROM public.idempotency_records
UNION ALL
SELECT 'cycle', COUNT(*) FROM public.cycle
UNION ALL
SELECT 'plan', COUNT(*) FROM public.plan
UNION ALL
SELECT 'sys_task', COUNT(*) FROM public.sys_task
UNION ALL
SELECT 'indicator', COUNT(*) FROM public.indicator
UNION ALL

SELECT 'attachment', COUNT(*) FROM public.attachment
UNION ALL
SELECT 'alert_event', COUNT(*) FROM public.alert_event
UNION ALL
SELECT 'plan_report', COUNT(*) FROM public.plan_report
UNION ALL
SELECT 'plan_report_indicator', COUNT(*) FROM public.plan_report_indicator
UNION ALL
SELECT 'plan_report_indicator_attachment', COUNT(*) FROM public.plan_report_indicator_attachment
UNION ALL
SELECT 'progress_report', COUNT(*) FROM public.progress_report
UNION ALL
SELECT 'audit_instance', COUNT(*) FROM public.audit_instance
UNION ALL
SELECT 'audit_step_instance', COUNT(*) FROM public.audit_step_instance
UNION ALL
SELECT 'workflow_task', COUNT(*) FROM public.workflow_task
UNION ALL
SELECT 'workflow_task_history', COUNT(*) FROM public.workflow_task_history
ORDER BY 1;

\echo ''
\echo '>>> 2. sys_task current-structure checks'
\echo ''

SELECT
    task_id,
    name,
    "desc",
    '[FAIL] name is null or blank' AS issue
FROM public.sys_task
WHERE name IS NULL OR BTRIM(name) = '';

SELECT
    task_id,
    cycle_id,
    '[FAIL] cycle_id points to missing cycle' AS issue
FROM public.sys_task t
LEFT JOIN public.cycle c ON c.id = t.cycle_id
WHERE c.id IS NULL;

SELECT
    task_id,
    org_id,
    '[FAIL] org_id points to missing sys_org' AS issue
FROM public.sys_task t
LEFT JOIN public.sys_org o ON o.id = t.org_id
WHERE o.id IS NULL;

SELECT
    task_id,
    created_by_org_id,
    '[FAIL] created_by_org_id points to missing sys_org' AS issue
FROM public.sys_task t
LEFT JOIN public.sys_org o ON o.id = t.created_by_org_id
WHERE o.id IS NULL;

SELECT
    task_id,
    plan_id,
    '[FAIL] plan_id points to missing plan' AS issue
FROM public.sys_task t
LEFT JOIN public.plan p ON p.id = t.plan_id
WHERE p.id IS NULL;

\echo ''
\echo '>>> 3. Indicator linkage checks'
\echo ''

SELECT
    i.id,
    i.task_id,
    '[FAIL] indicator.task_id points to missing sys_task' AS issue
FROM public.indicator i
LEFT JOIN public.sys_task t ON t.task_id = i.task_id
WHERE t.task_id IS NULL;

SELECT
    i.id,
    i.owner_org_id,
    '[FAIL] indicator.owner_org_id points to missing sys_org' AS issue
FROM public.indicator i
LEFT JOIN public.sys_org o ON o.id = i.owner_org_id
WHERE o.id IS NULL;

SELECT
    i.id,
    i.target_org_id,
    '[FAIL] indicator.target_org_id points to missing sys_org' AS issue
FROM public.indicator i
LEFT JOIN public.sys_org o ON o.id = i.target_org_id
WHERE o.id IS NULL;



\echo ''
\echo '>>> 5. Summary'
\echo ''

DO $$
DECLARE
    total_issues INT := 0;
    issue_count INT;
BEGIN
    SELECT COUNT(*) INTO issue_count FROM public.sys_task WHERE name IS NULL OR BTRIM(name) = '';
    total_issues := total_issues + issue_count;

    SELECT COUNT(*) INTO issue_count
    FROM public.sys_task t LEFT JOIN public.cycle c ON c.id = t.cycle_id
    WHERE c.id IS NULL;
    total_issues := total_issues + issue_count;

    SELECT COUNT(*) INTO issue_count
    FROM public.sys_task t LEFT JOIN public.sys_org o ON o.id = t.org_id
    WHERE o.id IS NULL;
    total_issues := total_issues + issue_count;

    SELECT COUNT(*) INTO issue_count
    FROM public.sys_task t LEFT JOIN public.sys_org o ON o.id = t.created_by_org_id
    WHERE o.id IS NULL;
    total_issues := total_issues + issue_count;

    SELECT COUNT(*) INTO issue_count
    FROM public.sys_task t LEFT JOIN public.plan p ON p.id = t.plan_id
    WHERE p.id IS NULL;
    total_issues := total_issues + issue_count;

    SELECT COUNT(*) INTO issue_count
    FROM public.indicator i LEFT JOIN public.sys_task t ON t.task_id = i.task_id
    WHERE t.task_id IS NULL;
    total_issues := total_issues + issue_count;

    SELECT COUNT(*) INTO issue_count
    FROM public.indicator i LEFT JOIN public.sys_org o ON o.id = i.owner_org_id
    WHERE o.id IS NULL;
    total_issues := total_issues + issue_count;

    SELECT COUNT(*) INTO issue_count
    FROM public.indicator i LEFT JOIN public.sys_org o ON o.id = i.target_org_id
    WHERE o.id IS NULL;
    total_issues := total_issues + issue_count;


    IF total_issues = 0 THEN
        RAISE NOTICE '[PASS] clean seed validation passed with zero issues';
    ELSE
        RAISE WARNING '[FAIL] clean seed validation found % issue(s)', total_issues;
    END IF;
END $$;
