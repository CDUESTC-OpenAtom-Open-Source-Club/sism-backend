-- =====================================================
-- 将 responsible_person 字段替换为 responsible_user_id
-- 版本: V37
-- 目的: 修复数据一致性问题，使用用户ID替代直接存储用户名
-- =====================================================

-- 1. 添加 responsible_user_id 字段
ALTER TABLE public.indicator ADD COLUMN IF NOT EXISTS responsible_user_id BIGINT;

-- 添加外键约束
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_indicator_responsible_user'
          AND conrelid = 'public.indicator'::regclass
    ) THEN
        ALTER TABLE public.indicator
        ADD CONSTRAINT fk_indicator_responsible_user
        FOREIGN KEY (responsible_user_id)
        REFERENCES public.sys_user(id);
    END IF;
END
$$;

-- 2. 从 sys_user 表匹配数据，更新 responsible_user_id
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'indicator'
          AND column_name = 'responsible_person'
    ) THEN
        UPDATE public.indicator i
        SET responsible_user_id = u.id
        FROM public.sys_user u
        WHERE i.responsible_person IS NOT NULL
          AND i.responsible_person = u.real_name;
    END IF;
END
$$;

-- 3. 检查更新结果
DO $$
DECLARE
    total_count INTEGER;
    updated_count INTEGER;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'indicator'
          AND column_name = 'responsible_person'
    ) THEN
        SELECT COUNT(*) INTO total_count FROM public.indicator WHERE responsible_person IS NOT NULL;
    ELSE
        total_count := 0;
    END IF;

    SELECT COUNT(*) INTO updated_count FROM public.indicator WHERE responsible_user_id IS NOT NULL;

    RAISE NOTICE 'Total records with responsible_person: %', total_count;
    RAISE NOTICE 'Records with matching responsible_user_id: %', updated_count;
    IF total_count > 0 THEN
        RAISE NOTICE 'Match rate: %%%', ROUND((updated_count::FLOAT / total_count::FLOAT) * 100);
    ELSE
        RAISE NOTICE 'Match rate: N/A';
    END IF;
END $$;

-- 4. 删除 responsible_person 字段（确保数据已迁移完成）
ALTER TABLE public.indicator DROP COLUMN IF EXISTS responsible_person;

COMMENT ON COLUMN public.indicator.responsible_user_id IS '负责人用户ID，关联sys_user表';
