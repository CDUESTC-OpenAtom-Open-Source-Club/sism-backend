DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'sys_user'
          AND column_name = 'email'
    ) THEN
        ALTER TABLE sys_user ADD COLUMN email VARCHAR(100);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'sys_user'
          AND column_name = 'phone'
    ) THEN
        ALTER TABLE sys_user ADD COLUMN phone VARCHAR(20);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_email
    ON sys_user (email)
    WHERE email IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_phone
    ON sys_user (phone)
    WHERE phone IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sys_user_email_login
    ON sys_user (email)
    WHERE email IS NOT NULL AND is_active = true;

CREATE INDEX IF NOT EXISTS idx_sys_user_phone_login
    ON sys_user (phone)
    WHERE phone IS NOT NULL AND is_active = true;
