-- Keep sys_task.task_id sequence aligned with live data to avoid duplicate key errors
SELECT setval(
    'public.strategic_task_task_id_seq',
    COALESCE((SELECT MAX(task_id) FROM public.sys_task), 1),
    true
);
