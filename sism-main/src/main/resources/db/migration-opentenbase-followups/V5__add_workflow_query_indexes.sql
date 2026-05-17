-- Speed up workflow snapshot and history lookups used by plan approval pages.

CREATE INDEX IF NOT EXISTS idx_audit_instance_entity_lookup
    ON public.audit_instance USING btree (entity_type, entity_id, started_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_audit_step_instance_pending_lookup
    ON public.audit_step_instance USING btree (instance_id, status, step_no ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_audit_step_instance_reject_lookup
    ON public.audit_step_instance USING btree (instance_id, status, approved_at DESC, created_at DESC, id DESC);
