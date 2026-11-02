-- =============================================================================
-- fix-ghost-drafts.sql —— 清理「审批通过后预建的同月幽灵草稿」上报轮次
-- =============================================================================
-- 用途（2026-10-31 P-19 配套数据修复）：
--   修复前的 ReportApplicationService.markWorkflowApproved 在审批通过后会把
--   同一 (plan_id, report_month, report_org_id, report_org_type) 预建一条
--   DRAFT 轮次。该预建轮次会被跳月校验（assertEarliestUnfilledMonth）当作
--   未结清欠账，把「当月」算成最早未填报月，阻塞下一个月的上报
--   （409「存在更早的未填报月份 yyyy-MM」）。代码侧已删除预建逻辑，
--   本脚本用于清理历史遗留的幽灵草稿行。
--
-- 幽灵草稿判定口径（与代码侧预建路径的产物特征一致）：
--   ① status = 'DRAFT'；
--   ② audit_instance_id IS NULL（预建行从未进入审批链）；
--   ③ submitted_at IS NULL 且 submitted_by IS NULL（从未上报）；
--   ④ 无任何指标明细（plan_report_indicator 中没有该 report_id 的行）；
--   ⑤ 同一 (plan_id, report_month, report_org_id, report_org_type) 范围内
--      存在另一条 APPROVED 轮次（即本次审批通过的产品）；
--   ⑥ is_deleted = false。
--
-- 风险与回滚：
--   - 删除的是「纯空壳草稿」（无明细、未上报、未进审批），不影响任何已
--     提交/已审批数据；填报入口 createReport 在下次填报时会按需重建轮次。
--   - 执行前必须先跑第一段 SELECT 人工核对清单；确认无误后再执行第二段。
--   - 建议在事务中执行并核对删除行数与第一段清单一致后再提交；
--     如需回滚，直接 ROLLBACK 即可（未提交前不落盘）。
--
-- 执行前提：
--   1. 已停机或锁定写入（避免执行期间产生新数据导致两段口径漂移）；
--   2. 已备份数据库（pg_dump 至少覆盖 public.plan_report /
--      public.plan_report_indicator 两表）；
--   3. 代码侧已部署删除预建逻辑之后的版本（否则会再次产生新幽灵草稿）。
--
-- 【声明】本脚本仅为交付物，未对任何数据库执行过。
-- =============================================================================

\set ON_ERROR_STOP on

-- -----------------------------------------------------------------------------
-- 第一段：纯 SELECT，列出待删清单（只读，不修改任何数据）
-- -----------------------------------------------------------------------------
SELECT
    g.id                AS ghost_report_id,
    g.plan_id,
    g.report_month,
    g.report_org_id,
    g.report_org_type,
    g.status            AS ghost_status,
    g.created_by,
    g.created_at,
    approved.id         AS approved_report_id,
    approved.status     AS approved_status
FROM public.plan_report g
JOIN public.plan_report approved
    ON approved.plan_id         = g.plan_id
   AND approved.report_month    = g.report_month
   AND approved.report_org_id   = g.report_org_id
   AND approved.report_org_type = g.report_org_type
   AND approved.id             <> g.id
   AND approved.is_deleted      = false
   AND approved.status          = 'APPROVED'
WHERE g.status = 'DRAFT'
  AND g.is_deleted = false
  AND g.audit_instance_id IS NULL
  AND g.submitted_at IS NULL
  AND g.submitted_by IS NULL
  AND NOT EXISTS (
        SELECT 1
        FROM public.plan_report_indicator pri
        WHERE pri.report_id = g.id
  )
ORDER BY g.plan_id, g.report_month, g.report_org_id, g.id;

-- 核对要点：
--   * 每一行的 approved_report_id 都应是刚刚审批通过的那一轮；
--   * ghost_report_id 应全部来自「审批通过时刻」附近（created_at 与
--     approved 轮的 approved_at 接近）；
--   * 若出现不属于该特征的行，先停下来人工排查，不要执行第二段。

-- -----------------------------------------------------------------------------
-- 第二段：事务包裹的删除（与第一段完全同条件的参数化删除）
-- 建议先跑 ROLLBACK 版本核对删除行数，确认无误后把末行 ROLLBACK 换成 COMMIT。
-- -----------------------------------------------------------------------------
BEGIN;

WITH ghosts AS (
    SELECT g.id
    FROM public.plan_report g
    WHERE g.status = 'DRAFT'
      AND g.is_deleted = false
      AND g.audit_instance_id IS NULL
      AND g.submitted_at IS NULL
      AND g.submitted_by IS NULL
      AND NOT EXISTS (
            SELECT 1
            FROM public.plan_report_indicator pri
            WHERE pri.report_id = g.id
      )
      AND EXISTS (
            SELECT 1
            FROM public.plan_report approved
            WHERE approved.plan_id         = g.plan_id
              AND approved.report_month    = g.report_month
              AND approved.report_org_id   = g.report_org_id
              AND approved.report_org_type = g.report_org_type
              AND approved.id             <> g.id
              AND approved.is_deleted      = false
              AND approved.status          = 'APPROVED'
      )
)
DELETE FROM public.plan_report g
WHERE g.id IN (SELECT id FROM ghosts);

-- 核对：DELETE 返回的行数必须与第一段 SELECT 清单行数一致。
-- 不一致说明两段执行之间数据发生了变化，ROLLBACK 并重新核对。

ROLLBACK;
-- 确认无误后，注释掉上一行 ROLLBACK; 并取消下一行注释提交事务：
-- COMMIT;
