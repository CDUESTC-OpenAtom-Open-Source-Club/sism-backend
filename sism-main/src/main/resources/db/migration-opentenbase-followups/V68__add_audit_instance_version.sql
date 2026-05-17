ALTER TABLE public.audit_instance
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
