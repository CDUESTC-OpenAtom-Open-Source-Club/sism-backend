ALTER TABLE public.sys_user
    ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500);

COMMENT ON COLUMN public.sys_user.avatar_url IS '用户头像 URL';
