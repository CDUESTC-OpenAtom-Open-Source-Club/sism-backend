-- =====================================================
-- 2026-10-21 用户定案：战略任务列（任务名称）与指标字段同一套异动口径
-- - 已下发计划上的任务名双击可改，改动攒内存草稿，随「发起异动」整批上报
-- - 任务改名异动与指标异动共用 PLAN_MUTATION_STRATEGY（战略部负责人 → 分管校领导）
-- - 审批期间任务打 IN_MUTATION 标记；终态（通过/驳回/撤回/退回）由业务适配器清除
-- =====================================================

ALTER TABLE public.sys_task ADD COLUMN IF NOT EXISTS mutation_status VARCHAR(20);
ALTER TABLE public.sys_task ADD COLUMN IF NOT EXISTS mutation_started_at TIMESTAMP;

ALTER TABLE public.sys_task
    DROP CONSTRAINT IF EXISTS sys_task_mutation_status_check;
ALTER TABLE public.sys_task
    ADD CONSTRAINT sys_task_mutation_status_check
    CHECK (mutation_status IS NULL OR mutation_status IN ('IN_MUTATION'));

COMMENT ON COLUMN public.sys_task.mutation_status IS '异动状态: NULL=无, IN_MUTATION=任务改名异动审批中';
COMMENT ON COLUMN public.sys_task.mutation_started_at IS '本次异动发起时间';
