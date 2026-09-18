-- =====================================================
-- P1 上报链改造：自评/鉴定进度等级 + 完成情况描述
-- 版本: V88
-- 口径依据《SISM 会议对齐 2026-09-17》：
-- - 填报人自评 + 上级鉴定共用三档进度等级：AHEAD(超前)/NORMAL(正常)/DELAYED(延期)
-- - 纯人工判定，不做自动计算；鉴定等级按审批节点留痕
-- - 月份规则（只能报最早未填报月）在应用层强校验，不依赖 DB 约束
-- 注意：旧预警档位（INFO/WARNING/CRITICAL）历史数据保持不动；
--       alert_event.severity 扩容 AHEAD/NORMAL 以承载「进度等级」三档语义
-- =====================================================

-- 1. 填报明细：自评等级 + 鉴定等级 + 完成情况描述
ALTER TABLE public.plan_report_indicator ADD COLUMN IF NOT EXISTS self_rating VARCHAR(16);
ALTER TABLE public.plan_report_indicator ADD COLUMN IF NOT EXISTS appraisal_level VARCHAR(16);
ALTER TABLE public.plan_report_indicator ADD COLUMN IF NOT EXISTS description TEXT;

ALTER TABLE public.plan_report_indicator
    DROP CONSTRAINT IF EXISTS plan_report_indicator_self_rating_check;
ALTER TABLE public.plan_report_indicator
    ADD CONSTRAINT plan_report_indicator_self_rating_check
    CHECK (self_rating IS NULL OR self_rating IN ('AHEAD', 'NORMAL', 'DELAYED'));

ALTER TABLE public.plan_report_indicator
    DROP CONSTRAINT IF EXISTS plan_report_indicator_appraisal_level_check;
ALTER TABLE public.plan_report_indicator
    ADD CONSTRAINT plan_report_indicator_appraisal_level_check
    CHECK (appraisal_level IS NULL OR appraisal_level IN ('AHEAD', 'NORMAL', 'DELAYED'));

COMMENT ON COLUMN public.plan_report_indicator.self_rating IS '自评进度等级: AHEAD=超前, NORMAL=正常, DELAYED=延期（填报人提交时人工判定）';
COMMENT ON COLUMN public.plan_report_indicator.appraisal_level IS '鉴定进度等级: AHEAD=超前, NORMAL=正常, DELAYED=延期（上级审批通过时人工判定，与自评分列留痕、互不覆盖）';
COMMENT ON COLUMN public.plan_report_indicator.description IS '完成情况描述（本月做了什么/到了哪一步/成果，纯文本，审批人鉴定的主要依据）';

-- 2. 审批节点实例：鉴定等级留痕（每个审批节点各自留值，便于自评 vs 鉴定对比）
ALTER TABLE public.audit_step_instance ADD COLUMN IF NOT EXISTS appraisal_level VARCHAR(16);

ALTER TABLE public.audit_step_instance
    DROP CONSTRAINT IF EXISTS audit_step_instance_appraisal_level_check;
ALTER TABLE public.audit_step_instance
    ADD CONSTRAINT audit_step_instance_appraisal_level_check
    CHECK (appraisal_level IS NULL OR appraisal_level IN ('AHEAD', 'NORMAL', 'DELAYED'));

COMMENT ON COLUMN public.audit_step_instance.appraisal_level IS '本节点鉴定进度等级: AHEAD=超前, NORMAL=正常, DELAYED=延期（审批通过时写入）';

-- 3. 预警事件 severity 扩容：新增 AHEAD/NORMAL 两档，承载「进度等级」语义
--    （原 INFO/WARNING/CRITICAL 保留，兼容历史数据与既有预警规则）
ALTER TABLE public.alert_event
    DROP CONSTRAINT IF EXISTS alert_event_severity_check;
ALTER TABLE public.alert_event
    ADD CONSTRAINT alert_event_severity_check
    CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL', 'AHEAD', 'NORMAL', 'DELAYED'));
