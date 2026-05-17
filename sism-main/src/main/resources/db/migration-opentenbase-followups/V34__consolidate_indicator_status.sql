-- =====================================================
-- 指标表状态字段优化脚本
-- 版本: V34
-- 目的: 合并状态字段，删除冗余字段
-- =====================================================

-- 1. 合并 distribution_status 到 status 字段
-- 基线库已经没有 distribution_status，老库仍可能存在。
ALTER TABLE public.indicator DROP COLUMN IF EXISTS distribution_status;

-- 为 status 字段添加检查约束，只允许有效状态值
ALTER TABLE public.indicator
DROP CONSTRAINT IF EXISTS indicator_status_check;

ALTER TABLE public.indicator
ADD CONSTRAINT indicator_status_check
CHECK (status IN ('DRAFT', 'PENDING', 'DISTRIBUTED', 'APPROVED', 'ARCHIVED'));

COMMENT ON COLUMN public.indicator.status IS '指标状态: DRAFT=草稿, PENDING=待审批, DISTRIBUTED=已下发, APPROVED=已通过, ARCHIVED=已归档';

-- 2. 删除 year 字段（冗余，来源于任务/计划）
ALTER TABLE public.indicator DROP COLUMN IF EXISTS year;

-- 3. 为 level 字段添加更清晰的注释和约束
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'indicator'
          AND column_name = 'level'
    ) THEN
        EXECUTE $sql$
            COMMENT ON COLUMN public.indicator.level
            IS '指标层级关系: PRIMARY=主指标, STRAT_TO_FUNC=战略到职能, FUNC_TO_COLLEGE=职能到学院'
        $sql$;

        UPDATE public.indicator
        SET level = CASE
            WHEN level IN ('PRIMARY', 'STRAT_TO_FUNC', 'FUNC_TO_COLLEGE') THEN level
            ELSE 'PRIMARY'
        END;

        EXECUTE 'ALTER TABLE public.indicator DROP CONSTRAINT IF EXISTS indicator_level_check';
        EXECUTE $sql$
            ALTER TABLE public.indicator
            ADD CONSTRAINT indicator_level_check
            CHECK (level IN ('PRIMARY', 'STRAT_TO_FUNC', 'FUNC_TO_COLLEGE'))
        $sql$;
    END IF;
END
$$;
