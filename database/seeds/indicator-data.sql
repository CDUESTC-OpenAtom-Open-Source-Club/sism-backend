-- indicator clean seed
-- Status rule:
-- - indicator.status is an indicator-level lifecycle/projection field.
-- - It is not the authoritative package status; plan.status is the main distribution state.
-- - Clean-seed baseline keeps the WHOLE matrix consistent: every plan container is DRAFT
--   (see plan-data.sql) and every indicator is DRAFT to match its parent plan container.
-- - Distribution is a runtime action (single / batch / import). Since the backend now
--   promotes the owning DRAFT plan to DISTRIBUTED whenever an indicator is distributed,
--   the two layers can no longer diverge ("指标已下发、计划未下发" 缺陷已由代码修复封堵).
-- 2026-11-27 作战拍板：业务演示指标清空，指标由各部门真实数据导入产生

BEGIN;

COMMIT;
