CREATE INDEX IF NOT EXISTS idx_sys_user_notification_recipient_status_created
    ON public.sys_user_notification (recipient_user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_step_instance_approver_status_instance
    ON public.audit_step_instance (approver_id, status, instance_id);
