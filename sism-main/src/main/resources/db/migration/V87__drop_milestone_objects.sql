-- V87: remove milestone feature storage after milestone code removal.
-- Applied only when this release is intentionally deployed; historical milestone data is deleted.
ALTER TABLE public.progress_report DROP CONSTRAINT IF EXISTS fk289plgq42or3890tc3n1a7nf5;
ALTER TABLE public.progress_report DROP COLUMN IF EXISTS milestone_id;
ALTER TABLE public.progress_report DROP COLUMN IF EXISTS achieved_milestone;
ALTER TABLE public.plan_report_indicator DROP COLUMN IF EXISTS milestone_note;
DROP TRIGGER IF EXISTS trg_milestone_updated_at ON public.indicator_milestone;
DROP INDEX IF EXISTS public.idx_milestone_due;
DROP INDEX IF EXISTS public.idx_milestone_indicator;
DROP INDEX IF EXISTS public.idx_milestone_indicator_id;
DROP INDEX IF EXISTS public.idx_milestone_status;
DROP TABLE IF EXISTS public.indicator_milestone;
DROP SEQUENCE IF EXISTS public.indicator_milestone_id_seq;
DROP TYPE IF EXISTS public.milestone_status;
