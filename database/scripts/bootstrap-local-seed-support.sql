-- bootstrap-local-seed-support.sql
-- Purpose:
-- - Fill in tables that are not created by the current JPA entity set
--   but are required by database/seeds/reset-and-load-clean-seeds.sql.
-- - Intended for local disposable databases only.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'plan_level') THEN
        CREATE TYPE plan_level AS ENUM (
            'STRATEGIC',
            'OPERATIONAL',
            'COMPREHENSIVE',
            'STRAT_TO_FUNC',
            'FUNC_TO_COLLEGE'
        );
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS public.sys_org (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(64) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    parent_org_id BIGINT,
    level INTEGER,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS public.plan (
    id BIGINT PRIMARY KEY,
    cycle_id BIGINT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    target_org_id BIGINT NOT NULL,
    created_by_org_id BIGINT NOT NULL,
    plan_level plan_level NOT NULL,
    status VARCHAR(64) NOT NULL,
    audit_instance_id BIGINT
);

CREATE TABLE IF NOT EXISTS public.sys_task (
    task_id BIGINT PRIMARY KEY,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    remark VARCHAR(255),
    sort_order INTEGER,
    name VARCHAR(255),
    "desc" TEXT,
    task_type VARCHAR(64),
    created_by_org_id BIGINT,
    cycle_id BIGINT,
    org_id BIGINT,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    task_category VARCHAR(64) NOT NULL DEFAULT 'STRATEGIC',
    status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
    plan_id BIGINT
);

CREATE TABLE IF NOT EXISTS public.warn_level (
    id BIGINT PRIMARY KEY,
    level_code VARCHAR(64) NOT NULL,
    level_name VARCHAR(128) NOT NULL,
    severity INTEGER NOT NULL,
    remark VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS public.alert_window (
    window_id BIGINT PRIMARY KEY,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    cutoff_date DATE NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    name VARCHAR(255) NOT NULL,
    cycle_id BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS public.alert_rule (
    rule_id BIGINT PRIMARY KEY,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    gap_threshold NUMERIC(10,2) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    name VARCHAR(255) NOT NULL,
    severity VARCHAR(64) NOT NULL,
    cycle_id BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS public.attachment (
    id BIGINT PRIMARY KEY,
    storage_driver VARCHAR(64),
    bucket VARCHAR(255),
    object_key VARCHAR(512),
    public_url VARCHAR(512),
    original_name VARCHAR(255),
    content_type VARCHAR(255),
    file_ext VARCHAR(32),
    size_bytes BIGINT,
    sha256 VARCHAR(64),
    etag VARCHAR(255),
    uploaded_by BIGINT,
    uploaded_at TIMESTAMP,
    remark VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.plan_report_indicator (
    id BIGINT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    indicator_id BIGINT NOT NULL,
    progress INTEGER,
    comment TEXT,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.plan_report_indicator_attachment (
    id BIGINT PRIMARY KEY,
    plan_report_indicator_id BIGINT NOT NULL,
    attachment_id BIGINT NOT NULL,
    sort_order INTEGER,
    created_by BIGINT,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    device_info VARCHAR(255),
    ip_address VARCHAR(45)
);

CREATE TABLE IF NOT EXISTS public.idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    http_method VARCHAR(10),
    idempotency_key VARCHAR(64) NOT NULL,
    request_path VARCHAR(255),
    response_body TEXT,
    status VARCHAR(20),
    status_code INTEGER,
    CONSTRAINT uk_ol0gjg0uap11mq1y9ug506f1i UNIQUE (idempotency_key),
    CONSTRAINT idempotency_records_status_check CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_idempotency_key
    ON public.idempotency_records (idempotency_key);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires_at
    ON public.idempotency_records (expires_at);

CREATE TABLE IF NOT EXISTS public.sys_user_notification (
    id BIGSERIAL PRIMARY KEY,
    recipient_user_id BIGINT NOT NULL,
    sender_user_id BIGINT,
    sender_org_id BIGINT,
    notification_type VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UNREAD',
    action_url VARCHAR(500),
    related_entity_type VARCHAR(64),
    related_entity_id BIGINT,
    metadata_json JSONB,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    batch_key VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_sys_user_notification_recipient_created
    ON public.sys_user_notification (recipient_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_sys_user_notification_related_entity
    ON public.sys_user_notification (related_entity_type, related_entity_id);

CREATE INDEX IF NOT EXISTS idx_sys_user_notification_batch_key
    ON public.sys_user_notification (batch_key);

ALTER TABLE public.sys_role
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

ALTER TABLE public.sys_user_role
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;

ALTER TABLE public.sys_role_permission
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;

ALTER TABLE public.audit_step_def
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

ALTER TABLE public.plan_report
    ADD COLUMN IF NOT EXISTS audit_instance_id BIGINT,
    ADD COLUMN IF NOT EXISTS remark VARCHAR(255),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_by BIGINT;

ALTER TABLE public.plan
    ADD COLUMN IF NOT EXISTS audit_instance_id BIGINT;

ALTER TABLE public.audit_instance
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

ALTER TABLE public.audit_step_instance
    ADD COLUMN IF NOT EXISTS approver_org_id BIGINT,
    ADD COLUMN IF NOT EXISTS step_no INTEGER;

ALTER TABLE public.audit_step_instance
    DROP COLUMN IF EXISTS step_index;

ALTER TABLE public.audit_step_instance
    DROP COLUMN IF EXISTS step_code;

ALTER TABLE public.workflow_task
    ADD COLUMN IF NOT EXISTS id BIGINT,
    ADD COLUMN IF NOT EXISTS task_id BIGINT;

UPDATE public.workflow_task
SET id = task_id
WHERE id IS NULL
  AND task_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.workflow_task'::regclass
          AND conname = 'workflow_task_id_key'
    ) THEN
        ALTER TABLE public.workflow_task
            ADD CONSTRAINT workflow_task_id_key UNIQUE (id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.workflow_task'::regclass
          AND conname = 'workflow_task_task_id_key'
    ) THEN
        ALTER TABLE public.workflow_task
            ADD CONSTRAINT workflow_task_task_id_key UNIQUE (task_id);
    END IF;
END
$$;
