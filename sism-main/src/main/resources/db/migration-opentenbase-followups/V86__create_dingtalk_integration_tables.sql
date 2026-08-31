-- DingTalk integration tables:
-- - sys_dingtalk_binding: maps SISM users to DingTalk org members (auto-bound by mobile on first dingtalk auth)
-- - sys_dingtalk_todo_task: tracks DingTalk todo tasks pushed for approval steps so they can be
--   completed (disappear) when the approval step/instance reaches a final state.

CREATE TABLE IF NOT EXISTS sys_dingtalk_binding (
    id BIGSERIAL PRIMARY KEY,
    sys_user_id BIGINT NOT NULL,
    dingtalk_user_id VARCHAR(64) NOT NULL,
    dingtalk_union_id VARCHAR(64),
    dingtalk_name VARCHAR(100),
    corp_id VARCHAR(64),
    mobile VARCHAR(20),
    bound_source VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    bound_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_sys_dingtalk_binding_user FOREIGN KEY (sys_user_id) REFERENCES sys_user(id),
    CONSTRAINT uk_sys_dingtalk_binding_user UNIQUE (sys_user_id),
    CONSTRAINT uk_sys_dingtalk_binding_dingtalk_user UNIQUE (dingtalk_user_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_dingtalk_binding_union
    ON sys_dingtalk_binding(dingtalk_union_id);

CREATE INDEX IF NOT EXISTS idx_sys_dingtalk_binding_mobile
    ON sys_dingtalk_binding(mobile);

CREATE TABLE IF NOT EXISTS sys_dingtalk_todo_task (
    id BIGSERIAL PRIMARY KEY,
    approval_instance_id BIGINT NOT NULL,
    step_instance_id BIGINT,
    sys_user_id BIGINT NOT NULL,
    dingtalk_union_id VARCHAR(64) NOT NULL,
    dingtalk_task_id VARCHAR(128) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    detail_url VARCHAR(500),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_sys_dingtalk_todo_user FOREIGN KEY (sys_user_id) REFERENCES sys_user(id)
);

CREATE INDEX IF NOT EXISTS idx_sys_dingtalk_todo_instance
    ON sys_dingtalk_todo_task(approval_instance_id);

CREATE INDEX IF NOT EXISTS idx_sys_dingtalk_todo_source
    ON sys_dingtalk_todo_task(source_id);

CREATE INDEX IF NOT EXISTS idx_sys_dingtalk_todo_user_status
    ON sys_dingtalk_todo_task(sys_user_id, status);
