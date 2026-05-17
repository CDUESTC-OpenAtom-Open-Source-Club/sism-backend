CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(6) NOT NULL,
    email VARCHAR(100) NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    location VARCHAR(100),
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP NULL,
    CONSTRAINT uk_reset_token UNIQUE (token, email)
);

CREATE INDEX IF NOT EXISTS idx_prt_user_id
    ON password_reset_tokens(user_id);

CREATE INDEX IF NOT EXISTS idx_prt_email_created_at
    ON password_reset_tokens(email, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_prt_expires
    ON password_reset_tokens(expires_at);

CREATE INDEX IF NOT EXISTS idx_prt_ip_created_at
    ON password_reset_tokens(ip_address, created_at DESC);
