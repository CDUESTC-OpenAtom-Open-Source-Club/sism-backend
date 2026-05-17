-- Enforce username policy at the database layer without shrinking the physical column yet.
-- Rule: 3-20 chars, letters/numbers/underscore only.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE table_schema = 'public'
          AND table_name = 'sys_user'
          AND constraint_name = 'ck_sys_user_username_policy'
    ) THEN
        ALTER TABLE public.sys_user
            ADD CONSTRAINT ck_sys_user_username_policy
            CHECK (username ~ '^[A-Za-z0-9_]{3,20}$') NOT VALID;
    END IF;
END $$;

ALTER TABLE public.sys_user
    VALIDATE CONSTRAINT ck_sys_user_username_policy;
