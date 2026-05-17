-- =====================================================
-- 从指标表移除 APPROVED 状态
-- 版本: V35
-- 目的: APPROVED 是审批表的状态，不应在指标表中
-- =====================================================

-- 1. 将所有 APPROVED 状态更新为 DISTRIBUTED
UPDATE public.indicator
SET status = 'DISTRIBUTED'
WHERE status = 'APPROVED';

-- 2. 将所有 ARCHIVED 状态更新为 DISTRIBUTED（归档也是在审批/工作流中管理的）
UPDATE public.indicator
SET status = 'DISTRIBUTED'
WHERE status = 'ARCHIVED';

-- 3. 更新约束，只保留指标表应该有的状态
ALTER TABLE public.indicator
DROP CONSTRAINT IF EXISTS indicator_status_check;

ALTER TABLE public.indicator
ADD CONSTRAINT indicator_status_check
CHECK (status IN ('DRAFT', 'PENDING', 'DISTRIBUTED'));

COMMENT ON COLUMN public.indicator.status IS '指标状态: DRAFT=草稿, PENDING=待审批, DISTRIBUTED=已下发（其他状态在审批流程中管理）';
