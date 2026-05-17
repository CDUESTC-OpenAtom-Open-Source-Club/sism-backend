-- =====================================================
-- 指标表字段清理与优化脚本 (自定义版本)
-- 版本: V32
-- 目的: 清理冗余字段，优化指标状态管理
-- =====================================================

-- 1. 删除冗余字段
DO $$
BEGIN
    -- 删除 type1 (与 type 重复)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='type1') THEN
        ALTER TABLE public.indicator DROP COLUMN type1;
        RAISE NOTICE '已删除 type1 字段';
    END IF;

    -- 删除 type2 (来源于任务的类型)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='type2') THEN
        ALTER TABLE public.indicator DROP COLUMN type2;
        RAISE NOTICE '已删除 type2 字段';
    END IF;

    -- 删除 owner_dept (冗余，通过 owner_org_id 关联获取)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='owner_dept') THEN
        ALTER TABLE public.indicator DROP COLUMN owner_dept;
        RAISE NOTICE '已删除 owner_dept 字段';
    END IF;

    -- 删除 responsible_dept (冗余，通过 target_org_id 关联获取)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='responsible_dept') THEN
        ALTER TABLE public.indicator DROP COLUMN responsible_dept;
        RAISE NOTICE '已删除 responsible_dept 字段';
    END IF;

    -- 删除 unit (单位 - 冗余，系统只跟踪进度百分比)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='unit') THEN
        ALTER TABLE public.indicator DROP COLUMN unit;
        RAISE NOTICE '已删除 unit 字段';
    END IF;

    -- 删除 actual_value (实际值 - 冗余，进度通过 ProgressReport 跟踪)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='actual_value') THEN
        ALTER TABLE public.indicator DROP COLUMN actual_value;
        RAISE NOTICE '已删除 actual_value 字段';
    END IF;

    -- 删除 target_value (目标值 - 冗余，目标通过 Milestone.targetProgress 定义)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='target_value') THEN
        ALTER TABLE public.indicator DROP COLUMN target_value;
        RAISE NOTICE '已删除 target_value 字段';
    END IF;
END $$;

-- 2. 优化 distribution_status 字段，只保留有效状态值
-- 旧状态值: DRAFT=草稿未下发, DISTRIBUTED=已下发, PENDING=待审批, APPROVED=已通过, REJECTED=已驳回, NOT_DISTRIBUTED=未下发
-- 新状态值: DRAFT=草稿未下发, DISTRIBUTED=已下发, PENDING=待审批 (其他状态在审批流程中)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='distribution_status') THEN
        -- 更新现有记录的无效状态
        UPDATE public.indicator
        SET distribution_status = CASE
            WHEN distribution_status IN ('APPROVED', 'REJECTED', 'NOT_DISTRIBUTED') THEN 'DISTRIBUTED'
            ELSE distribution_status
        END;

        -- 先删除旧的检查约束（如果存在）
        IF EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE table_name = 'indicator' AND constraint_name = 'indicator_distribution_status_check') THEN
            ALTER TABLE public.indicator DROP CONSTRAINT indicator_distribution_status_check;
        END IF;

        -- 添加检查约束，只允许有效状态值
        ALTER TABLE public.indicator
        ADD CONSTRAINT indicator_distribution_status_check
        CHECK (distribution_status IN ('DRAFT', 'DISTRIBUTED', 'PENDING'));

        COMMENT ON COLUMN public.indicator.distribution_status IS '指标下发状态: DRAFT=草稿未下发, DISTRIBUTED=已下发, PENDING=待审批';

        RAISE NOTICE '已优化 distribution_status 字段，只保留有效状态值';
    END IF;
END $$;

-- 3. 优化 type 字段，确保只存储定量/定性
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='type') THEN
        -- 标准化类型值
        UPDATE public.indicator
        SET type = CASE
            WHEN type IN ('定性', 'QUALITATIVE') THEN '定性'
            ELSE '定量'
        END;

        -- 先删除旧的检查约束（如果存在）
        IF EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE table_name = 'indicator' AND constraint_name = 'indicator_type_check') THEN
            ALTER TABLE public.indicator DROP CONSTRAINT indicator_type_check;
        END IF;

        -- 添加检查约束
        ALTER TABLE public.indicator
        ADD CONSTRAINT indicator_type_check
        CHECK (type IN ('定量', '定性'));

        COMMENT ON COLUMN public.indicator.type IS '指标类型: 定量/定性';

        RAISE NOTICE '已优化 type 字段，只允许定量/定性';
    END IF;
END $$;

-- 4. 确保核心字段的完整性约束
DO $$
BEGIN
    -- 确保 type 字段有默认值
    ALTER TABLE public.indicator ALTER COLUMN type SET DEFAULT '定量';

    -- 确保 year 字段不为空
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'indicator' AND column_name = 'year' AND is_nullable = 'YES') THEN
        -- 先更新 NULL 值为当前年份
        UPDATE public.indicator SET year = EXTRACT(YEAR FROM CURRENT_DATE) WHERE year IS NULL;
        ALTER TABLE public.indicator ALTER COLUMN year SET NOT NULL;
    END IF;

    -- 确保 progress 在有效范围内
    ALTER TABLE public.indicator ALTER COLUMN progress SET DEFAULT 0;
    UPDATE public.indicator SET progress = 0 WHERE progress IS NULL;
    UPDATE public.indicator SET progress = 0 WHERE progress < 0;
    UPDATE public.indicator SET progress = 100 WHERE progress > 100;

    -- 先删除旧的检查约束（如果存在）
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE table_name = 'indicator' AND constraint_name = 'indicator_progress_check') THEN
        ALTER TABLE public.indicator DROP CONSTRAINT indicator_progress_check;
    END IF;

    ALTER TABLE public.indicator
    ADD CONSTRAINT indicator_progress_check CHECK (progress >= 0 AND progress <= 100);
END $$;

-- 5. 验证更新结果
DO $$
DECLARE
    v_quantitative_count INTEGER;
    v_qualitative_count INTEGER;
    v_draft_count INTEGER;
    v_distributed_count INTEGER;
    v_pending_count INTEGER;
    v_status_column TEXT;
BEGIN
    RAISE NOTICE '====================================================';
    RAISE NOTICE '指标表字段清理完成！';
    RAISE NOTICE '====================================================';

    -- 统计指标类型分布
    SELECT COUNT(*) INTO v_quantitative_count FROM public.indicator WHERE type = '定量';
    SELECT COUNT(*) INTO v_qualitative_count FROM public.indicator WHERE type = '定性';
    RAISE NOTICE '指标类型分布:';
    RAISE NOTICE '  定量指标: %', v_quantitative_count;
    RAISE NOTICE '  定性指标: %', v_qualitative_count;

    -- 统计状态分布。空库基线已经只保留 status，老库仍可能保留 distribution_status。
    SELECT CASE
        WHEN EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_name = 'indicator' AND column_name = 'distribution_status'
        ) THEN 'distribution_status'
        ELSE 'status'
    END
    INTO v_status_column;

    IF v_status_column = 'distribution_status' THEN
        SELECT COUNT(*) INTO v_draft_count FROM public.indicator WHERE distribution_status = 'DRAFT';
        SELECT COUNT(*) INTO v_distributed_count FROM public.indicator WHERE distribution_status = 'DISTRIBUTED';
        SELECT COUNT(*) INTO v_pending_count FROM public.indicator WHERE distribution_status = 'PENDING';
    ELSE
        SELECT COUNT(*) INTO v_draft_count FROM public.indicator WHERE status = 'DRAFT';
        SELECT COUNT(*) INTO v_distributed_count FROM public.indicator WHERE status = 'DISTRIBUTED';
        SELECT COUNT(*) INTO v_pending_count FROM public.indicator WHERE status = 'PENDING';
    END IF;

    RAISE NOTICE '下发状态分布:';
    RAISE NOTICE '  DRAFT(草稿未下发): %', v_draft_count;
    RAISE NOTICE '  DISTRIBUTED(已下发): %', v_distributed_count;
    RAISE NOTICE '  PENDING(待审批): %', v_pending_count;

    RAISE NOTICE '====================================================';
END $$;
