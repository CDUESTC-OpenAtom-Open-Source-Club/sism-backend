-- V83: 系统维护公告表
CREATE TABLE IF NOT EXISTS public.sys_announcement (
    id              BIGSERIAL       PRIMARY KEY,
    title           VARCHAR(255)    NOT NULL,
    content         TEXT            NOT NULL,
    status          VARCHAR(32)     NOT NULL DEFAULT 'DRAFT',
    scheduled_at    TIMESTAMP,
    published_at    TIMESTAMP,
    created_by      BIGINT          NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  public.sys_announcement                   IS '系统维护公告';
COMMENT ON COLUMN public.sys_announcement.id                IS '主键ID';
COMMENT ON COLUMN public.sys_announcement.title             IS '公告标题';
COMMENT ON COLUMN public.sys_announcement.content           IS '公告内容（支持富文本）';
COMMENT ON COLUMN public.sys_announcement.status            IS '状态：DRAFT / PUBLISHED / WITHDRAWN';
COMMENT ON COLUMN public.sys_announcement.scheduled_at      IS '定时发布时间（null表示立即或手动发布）';
COMMENT ON COLUMN public.sys_announcement.published_at      IS '实际发布时间';
COMMENT ON COLUMN public.sys_announcement.created_by        IS '创建人用户ID';
COMMENT ON COLUMN public.sys_announcement.created_at        IS '创建时间';
COMMENT ON COLUMN public.sys_announcement.updated_at        IS '更新时间';

CREATE INDEX idx_sys_announcement_status ON public.sys_announcement (status);
CREATE INDEX idx_sys_announcement_scheduled ON public.sys_announcement (scheduled_at) WHERE scheduled_at IS NOT NULL;
