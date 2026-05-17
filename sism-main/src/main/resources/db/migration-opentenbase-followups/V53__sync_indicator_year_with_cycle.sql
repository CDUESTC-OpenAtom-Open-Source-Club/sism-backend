-- Keep indicator.year aligned with the authoritative cycle.year carried by sys_task.cycle_id.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'indicator'
          AND column_name = 'year'
    ) THEN
        UPDATE public.indicator AS i
        SET year = c.year
        FROM public.sys_task AS t
        JOIN public.cycle AS c ON c.id = t.cycle_id
        WHERE i.task_id = t.task_id
          AND i.year IS DISTINCT FROM c.year;

        EXECUTE $sql$
            CREATE OR REPLACE FUNCTION public.resolve_indicator_year_from_task(p_task_id BIGINT)
            RETURNS INTEGER
            LANGUAGE SQL
            STABLE
            AS $fn$
                SELECT c.year
                FROM public.sys_task t
                JOIN public.cycle c ON c.id = t.cycle_id
                WHERE t.task_id = p_task_id
                LIMIT 1;
            $fn$
        $sql$;

        EXECUTE $sql$
            CREATE OR REPLACE FUNCTION public.sync_indicator_year_from_task()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS $fn$
            BEGIN
                IF NEW.task_id IS NULL THEN
                    RETURN NEW;
                END IF;

                NEW.year := public.resolve_indicator_year_from_task(NEW.task_id);
                RETURN NEW;
            END;
            $fn$
        $sql$;

        EXECUTE 'DROP TRIGGER IF EXISTS trg_indicator_sync_year_from_task ON public.indicator';
        EXECUTE $sql$
            CREATE TRIGGER trg_indicator_sync_year_from_task
            BEFORE INSERT OR UPDATE OF task_id
            ON public.indicator
            FOR EACH ROW
            EXECUTE FUNCTION public.sync_indicator_year_from_task()
        $sql$;

        EXECUTE $sql$
            CREATE OR REPLACE FUNCTION public.refresh_indicator_years_for_task()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS $fn$
            BEGIN
                UPDATE public.indicator
                SET year = public.resolve_indicator_year_from_task(NEW.task_id)
                WHERE task_id = NEW.task_id;

                RETURN NEW;
            END;
            $fn$
        $sql$;

        EXECUTE 'DROP TRIGGER IF EXISTS trg_sys_task_refresh_indicator_year ON public.sys_task';
        EXECUTE $sql$
            CREATE TRIGGER trg_sys_task_refresh_indicator_year
            AFTER INSERT OR UPDATE OF cycle_id
            ON public.sys_task
            FOR EACH ROW
            EXECUTE FUNCTION public.refresh_indicator_years_for_task()
        $sql$;

        EXECUTE $sql$
            CREATE OR REPLACE FUNCTION public.refresh_indicator_years_for_cycle()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS $fn$
            BEGIN
                UPDATE public.indicator AS i
                SET year = NEW.year
                FROM public.sys_task AS t
                WHERE i.task_id = t.task_id
                  AND t.cycle_id = NEW.id;

                RETURN NEW;
            END;
            $fn$
        $sql$;

        EXECUTE 'DROP TRIGGER IF EXISTS trg_cycle_refresh_indicator_year ON public.cycle';
        EXECUTE $sql$
            CREATE TRIGGER trg_cycle_refresh_indicator_year
            AFTER UPDATE OF year
            ON public.cycle
            FOR EACH ROW
            EXECUTE FUNCTION public.refresh_indicator_years_for_cycle()
        $sql$;

        EXECUTE $sql$
            COMMENT ON COLUMN public.indicator.year
            IS '指标所属年份；与 sys_task.cycle_id -> cycle.year 保持同步'
        $sql$;
    END IF;
END
$$;
