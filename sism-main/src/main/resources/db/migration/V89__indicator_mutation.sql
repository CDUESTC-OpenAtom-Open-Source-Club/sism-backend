-- =====================================================
-- P5 指标异动：原地修改锁标记（修改中状态）
-- 口径依据《SISM 会议对齐 2026-09-17》：
-- - 异动只能在「已下发 + 稳定态」发起；审批期间全面锁死填报/提交
-- - 变更快照写入 audit_log（before_json/after_json/changed_fields）
-- =====================================================

ALTER TABLE public.indicator ADD COLUMN IF NOT EXISTS mutation_status VARCHAR(20);
ALTER TABLE public.indicator ADD COLUMN IF NOT EXISTS mutation_started_at TIMESTAMP;

ALTER TABLE public.indicator
    DROP CONSTRAINT IF EXISTS indicator_mutation_status_check;
ALTER TABLE public.indicator
    ADD CONSTRAINT indicator_mutation_status_check
    CHECK (mutation_status IS NULL OR mutation_status IN ('IN_MUTATION'));

COMMENT ON COLUMN public.indicator.mutation_status IS '异动状态: NULL=无, IN_MUTATION=异动审批中（期间该组织锁死填报/提交）';
COMMENT ON COLUMN public.indicator.mutation_started_at IS '本次异动发起时间';
