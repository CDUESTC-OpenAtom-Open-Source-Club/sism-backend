-- Analytics dashboard read models.
-- These views give the analytics context a stable query surface instead of
-- hard-coding direct reads against other bounded-context tables.

CREATE OR REPLACE VIEW public.analytics_indicator_dashboard_view AS
SELECT
    i.id,
    i.target_org_id,
    i.indicator_desc,
    i.status,
    i.progress,
    i.type,
    i.updated_at
FROM public.indicator i
WHERE COALESCE(i.is_deleted, false) = false;

CREATE OR REPLACE VIEW public.analytics_unresolved_alert_dashboard_view AS
SELECT
    ae.event_id,
    ae.indicator_id,
    ae.severity,
    ae.status
FROM public.alert_event ae
WHERE ae.status <> 'RESOLVED';

CREATE OR REPLACE VIEW public.analytics_active_org_dashboard_view AS
SELECT
    o.id,
    o.name
FROM public.sys_org o
WHERE COALESCE(o.is_deleted, false) = false
  AND COALESCE(o.is_active, true) = true;
