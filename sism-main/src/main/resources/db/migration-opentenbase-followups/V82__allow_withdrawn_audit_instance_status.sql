ALTER TABLE public.audit_instance
    DROP CONSTRAINT IF EXISTS audit_instance_status_check;

ALTER TABLE public.audit_instance
    ADD CONSTRAINT audit_instance_status_check CHECK (
        (status)::text = ANY (
            ARRAY[
                ('IN_REVIEW'::character varying)::text,
                ('APPROVED'::character varying)::text,
                ('REJECTED'::character varying)::text,
                ('WITHDRAWN'::character varying)::text
            ]
        )
    );

COMMENT ON COLUMN public.audit_instance.status IS '审批实例状态：IN_REVIEW / APPROVED / REJECTED / WITHDRAWN';
