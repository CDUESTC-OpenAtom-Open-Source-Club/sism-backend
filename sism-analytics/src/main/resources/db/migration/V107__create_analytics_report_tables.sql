-- =====================================================
-- 简报模块建表：为分析报告 / 仪表板 / 数据导出补建真实存储表
-- （2026-11-23 用户授权新增；不改动任何既有迁移与既有表）
--
-- 缺陷（D1，线上回归实证）：
--   GET/POST /api/v1/analytics/reports 500，Hibernate 按 progress_report.id
--   查询报「column does not exist」。字段级核对结论：Report 实体映射的
--   id/report_name/report_type/report_format/file_path/file_size/creator_id/
--   generation_time/report_params/description/is_deleted 共 11 列在全部
--   Flyway DDL 中均不存在；真实 progress_report 是执行侧进展填报表
--   （主键 report_id，indicator_id/reporter_id/percent_complete 等 NOT NULL，
--   status 受 CHECK 约束），分析报告无法落库。
--   同类错位：DataExport 实体挂在 adhoc_task（执行侧临时任务表，列全不匹配）；
--   Dashboard 实体指向的 analytics_dashboards 表从未存在。
--   旧测试全绿是因为测试 profile 用 ddl-auto=create-drop 让 Hibernate 按
--   实体（错位映射）建了假表，掩盖了与真实库的偏差。
--
-- 修复策略（库表是对的，实体是错的）：
--   progress_report / adhoc_task 一列不动，归还执行侧；
--   简报模块三实体各建自己的表，实体仅改 @Table 指向新表。
--   V69 在 progress_report 上加的 error_message 列保留不回收（无害）。
--   OpenTenbase 部署链（migration-opentenbase-followups）当前止步 V103，
--   V104-V106 与本迁移同策略仅入主链，需对齐时另行同步。
-- =====================================================

CREATE TABLE IF NOT EXISTS public.analytics_report (
    id              BIGSERIAL PRIMARY KEY,
    report_name     VARCHAR(255) NOT NULL,
    report_type     VARCHAR(50)  NOT NULL,
    report_format   VARCHAR(50)  NOT NULL,
    status          VARCHAR(50)  NOT NULL,
    file_path       VARCHAR(1000),
    file_size       BIGINT,
    creator_id      BIGINT,
    generation_time TIMESTAMP,
    report_params   TEXT,
    description     VARCHAR(1000),
    error_message   VARCHAR(1000),
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.analytics_dashboard (
    id             BIGSERIAL PRIMARY KEY,
    dashboard_name VARCHAR(255) NOT NULL,
    description    VARCHAR(1000),
    user_id        BIGINT NOT NULL,
    is_public      BOOLEAN NOT NULL DEFAULT FALSE,
    config         TEXT,
    is_deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.data_export (
    id               BIGSERIAL PRIMARY KEY,
    task_name        VARCHAR(255) NOT NULL,
    task_type        VARCHAR(50)  NOT NULL,
    export_format    VARCHAR(50)  NOT NULL,
    status           VARCHAR(50)  NOT NULL,
    result_file_path VARCHAR(1000),
    result_file_size BIGINT,
    creator_id       BIGINT NOT NULL,
    created_at       TIMESTAMP NOT NULL,
    start_time       TIMESTAMP,
    end_time         TIMESTAMP,
    error_log        VARCHAR(1000),
    task_params      TEXT,
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_analytics_report_status    ON public.analytics_report (status);
CREATE INDEX IF NOT EXISTS idx_analytics_report_creator   ON public.analytics_report (creator_id);
CREATE INDEX IF NOT EXISTS idx_analytics_dashboard_user   ON public.analytics_dashboard (user_id);
CREATE INDEX IF NOT EXISTS idx_data_export_creator        ON public.data_export (creator_id);
