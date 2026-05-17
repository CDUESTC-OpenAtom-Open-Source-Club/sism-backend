ALTER TABLE public.cycle
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE public.cycle
SET status = CASE
    WHEN end_date < CURRENT_DATE THEN 'COMPLETED'
    WHEN start_date > CURRENT_DATE THEN 'UPCOMING'
    ELSE 'ACTIVE'
END
WHERE status IS NULL OR status = '';

ALTER TABLE public.plan_report
    ADD COLUMN IF NOT EXISTS title VARCHAR(500),
    ADD COLUMN IF NOT EXISTS content TEXT,
    ADD COLUMN IF NOT EXISTS summary TEXT,
    ADD COLUMN IF NOT EXISTS progress INTEGER,
    ADD COLUMN IF NOT EXISTS issues TEXT,
    ADD COLUMN IF NOT EXISTS next_plan TEXT,
    ADD COLUMN IF NOT EXISTS submitted_by BIGINT,
    ADD COLUMN IF NOT EXISTS approved_by BIGINT,
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

ALTER TABLE public.plan_report
    DROP CONSTRAINT IF EXISTS plan_report_status_check;

ALTER TABLE public.plan_report
    ADD CONSTRAINT plan_report_status_check
    CHECK (((status)::text = ANY (ARRAY[
        ('DRAFT'::character varying)::text,
        ('SUBMITTED'::character varying)::text,
        ('IN_REVIEW'::character varying)::text,
        ('APPROVED'::character varying)::text,
        ('REJECTED'::character varying)::text
    ])));

UPDATE public.plan_report
SET status = 'SUBMITTED'
WHERE status = 'IN_REVIEW';
