-- =====================================================
-- 2026-10-21 用户定案：一次「发起异动」提交的多条改动要聚合成一张审批卡片
-- - 批量异动此前为每条指标/任务各写一条 audit_log、各起一个审批实例，彼此无关联
-- - 新增 batch_id 把同一次提交的快照与实例串起来：
--   * audit_log.batch_id         → 按批次取全部 before/after 快照（放弃回滚也依赖它）
--   * audit_instance.batch_id    → 审批中心按批次聚合成一张卡片
-- 历史数据 batch_id 为 NULL，前端按「单条独立卡片」兼容展示。
-- =====================================================

ALTER TABLE public.audit_log ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64);
ALTER TABLE public.audit_instance ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64);

COMMENT ON COLUMN public.audit_log.batch_id IS '异动批次号：同一次「发起异动」提交的多条快照共享同一值';
COMMENT ON COLUMN public.audit_instance.batch_id IS '异动批次号：同一次提交产生的多个审批实例共享同一值，供审批中心聚合';
