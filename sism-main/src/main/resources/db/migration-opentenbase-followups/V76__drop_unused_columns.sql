ALTER TABLE public.plan
    DROP COLUMN IF EXISTS last_reject_reason,
    DROP COLUMN IF EXISTS submitted_at,
    DROP COLUMN IF EXISTS submitted_by,
    DROP COLUMN IF EXISTS workflow_instance_id;

ALTER TABLE public.sys_task
    DROP COLUMN IF EXISTS task_name,
    DROP COLUMN IF EXISTS task_desc;

ALTER TABLE public.cycle
    DROP COLUMN IF EXISTS is_deleted,
    DROP COLUMN IF EXISTS status;
