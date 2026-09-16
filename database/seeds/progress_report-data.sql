-- progress_report clean seed
-- Scope:
-- - progress_report is an execution-side progress submission table.
-- - Clean seed baseline keeps this table empty.

BEGIN;

INSERT INTO public.progress_report (
    report_id,
    is_final,
    narrative,
    percent_complete,
    reported_at,
    status,
    error_message,
    version_no,
    adhoc_task_id,
    indicator_id,
    reporter_id,
    created_at,
    updated_at
)
SELECT
    NULL::bigint,
    NULL::boolean,
    NULL::boolean,
    NULL::text,
    NULL::numeric,
    NULL::timestamp,
    NULL::varchar,
    NULL::text,
    NULL::bigint,
    NULL::bigint,
    NULL::bigint,
    NULL::bigint,
    NULL::bigint,
    NULL::timestamp,
    NULL::timestamp
WHERE FALSE;

COMMIT;
