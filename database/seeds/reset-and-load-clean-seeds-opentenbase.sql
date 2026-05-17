-- OpenTenBase variant of the clean-seed reset script.
-- Difference from reset-and-load-clean-seeds.sql:
-- - PGXC/OpenTenBase does not support TRUNCATE ... RESTART IDENTITY
-- - explicit-ID seed files are still followed by manual sequence resync

\set ON_ERROR_STOP on

BEGIN;

TRUNCATE TABLE
    public.workflow_task_history,
    public.workflow_task,
    public.sys_user_notification,
    public.idempotency_records,
    public.adhoc_task_indicator_map,
    public.adhoc_task_target,
    public.adhoc_task,
    public.audit_step_instance,
    public.audit_instance,
    public.alert_event,
    public.plan_report_indicator_attachment,
    public.attachment,
    public.plan_report_indicator,
    public.plan_report,
    public.alert_rule,
    public.alert_window,
    public.warn_level,
    public.indicator_milestone,
    public.indicator,
    public.sys_task,
    public.plan,
    public.cycle,
    public.audit_step_def,
    public.sys_user_role,
    public.sys_role_permission,
    public.audit_flow_def,
    public.sys_permission,
    public.sys_role,
    public.password_reset_tokens,
    public.sys_user,
    public.sys_org,
    public.progress_report,
    public.audit_log,
    public.refresh_tokens
CASCADE;

COMMIT;

\i sys_org-data.sql
\i sys_user-data.sql
\i sys_user_notification-data.sql
\i idempotency_records-data.sql
\i sys_role-data.sql
\i sys_permission-data.sql
\i audit_flow_def-data.sql
\i sys_role_permission-data.sql
\i sys_user_role-data.sql
\i sys_demo_user-data.sql
\i audit_step_def-data.sql
\i cycle-data.sql
\i plan-data.sql
\i sys_task-data.sql
\i indicator-data.sql
\i indicator_milestone-data.sql
\i warn_level-data.sql
\i alert_window-data.sql
\i alert_rule-data.sql
\i plan_report-data.sql
\i plan_report_indicator-data.sql
\i attachment-data.sql
\i plan_report_indicator_attachment-data.sql
\i alert_event-data.sql
\i audit_instance-data.sql
\i audit_step_instance-data.sql
\i adhoc_task-data.sql
\i adhoc_task_indicator_map-data.sql
\i adhoc_task_target-data.sql
\i workflow_task-data.sql
\i workflow_task_history-data.sql
\i progress_report-data.sql

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT *
        FROM (
            VALUES
                ('public.sys_org', 'id'),
                ('public.sys_user', 'id'),
                ('public.sys_role', 'id'),
                ('public.sys_permission', 'id'),
                ('public.audit_flow_def', 'id'),
                ('public.audit_step_def', 'id'),
                ('public.cycle', 'id'),
                ('public.plan', 'id'),
                ('public.sys_task', 'task_id'),
                ('public.indicator', 'id'),
                ('public.indicator_milestone', 'id'),
                ('public.warn_level', 'id'),
                ('public.alert_window', 'window_id'),
                ('public.alert_rule', 'rule_id'),
                ('public.plan_report', 'id'),
                ('public.plan_report_indicator', 'id'),
                ('public.attachment', 'id'),
                ('public.plan_report_indicator_attachment', 'id'),
                ('public.alert_event', 'event_id'),
                ('public.audit_instance', 'id'),
                ('public.audit_step_instance', 'id'),
                ('public.adhoc_task', 'adhoc_task_id')
        ) AS t(tbl, col)
    LOOP
        IF pg_get_serial_sequence(rec.tbl, rec.col) IS NOT NULL THEN
            EXECUTE format(
                'SELECT setval(%L, COALESCE(MAX(%I), 1), MAX(%I) IS NOT NULL) FROM %s',
                pg_get_serial_sequence(rec.tbl, rec.col),
                rec.col,
                rec.col,
                rec.tbl
            );
        END IF;
    END LOOP;
END
$$;
