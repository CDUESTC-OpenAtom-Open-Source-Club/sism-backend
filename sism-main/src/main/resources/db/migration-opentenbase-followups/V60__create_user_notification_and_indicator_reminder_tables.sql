CREATE TABLE IF NOT EXISTS sys_user_notification (
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
    batch_key VARCHAR(64),
    metadata_json JSONB,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sys_user_notification_recipient_created
    ON sys_user_notification(recipient_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_sys_user_notification_related_entity
    ON sys_user_notification(related_entity_type, related_entity_id);

CREATE INDEX IF NOT EXISTS idx_sys_user_notification_batch_key
    ON sys_user_notification(batch_key);

DROP TABLE IF EXISTS indicator_reminder_record;
