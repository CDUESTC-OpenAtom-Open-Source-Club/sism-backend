-- =====================================================
-- V91: alert_rule.severity 约束扩容（三档进度等级 AHEAD/NORMAL/DELAYED）
--
-- 背景：V88 只扩了 alert_event.severity 的 CHECK，漏了 alert_rule。
-- 手动进度等级（setManualAlertLevel）会向 alert_rule 写入三档 rule
-- （AlertApplicationService.resolveManualAlertRuleId，threshold=0），
-- 旧约束下 INSERT 会直接违反 alert_rule_severity_check。
-- =====================================================

ALTER TABLE public.alert_rule
    DROP CONSTRAINT IF EXISTS alert_rule_severity_check;

ALTER TABLE public.alert_rule
    ADD CONSTRAINT alert_rule_severity_check
    CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL', 'AHEAD', 'NORMAL', 'DELAYED'));
