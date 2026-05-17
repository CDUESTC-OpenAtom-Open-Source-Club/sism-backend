DROP TRIGGER IF EXISTS trg_indicator_sync_year_from_task ON public.indicator;
DROP TRIGGER IF EXISTS trg_sys_task_refresh_indicator_year ON public.sys_task;
DROP TRIGGER IF EXISTS trg_cycle_refresh_indicator_year ON public.cycle;

DROP FUNCTION IF EXISTS public.sync_indicator_year_from_task();
DROP FUNCTION IF EXISTS public.refresh_indicator_years_for_task();
DROP FUNCTION IF EXISTS public.refresh_indicator_years_for_cycle();
DROP FUNCTION IF EXISTS public.resolve_indicator_year_from_task(BIGINT);

DROP INDEX IF EXISTS idx_indicator_year;

ALTER TABLE public.indicator
DROP COLUMN IF EXISTS year;
