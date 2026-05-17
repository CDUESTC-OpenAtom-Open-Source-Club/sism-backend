-- Allow multiple monthly report rounds within the same plan/org scope.
-- Once a round reaches APPROVED or REJECTED, subsequent filling should
-- create a brand-new plan_report row instead of reusing the historical one.

ALTER TABLE public.plan_report
    DROP CONSTRAINT IF EXISTS uq_plan_report;

CREATE INDEX IF NOT EXISTS idx_plan_report_scope_created
    ON public.plan_report (plan_id, report_month, report_org_type, report_org_id, created_at DESC, id DESC);
