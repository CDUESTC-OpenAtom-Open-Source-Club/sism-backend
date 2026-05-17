-- =====================================================
-- 从指标表删除 level 字段
-- 版本: V36
-- 目的: level 字段是冗余的，通过 owner_org_id 和 target_org_id 关联部门表可判断层级
-- =====================================================

-- 删除 level 字段
ALTER TABLE public.indicator DROP COLUMN IF EXISTS level;
