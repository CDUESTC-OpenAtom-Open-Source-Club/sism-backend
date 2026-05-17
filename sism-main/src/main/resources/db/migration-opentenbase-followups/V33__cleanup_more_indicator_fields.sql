-- =====================================================
-- 指标表字段清理脚本 - 第二部分
-- 版本: V33
-- 目的: 清理更多冗余字段
-- =====================================================

DO $$
BEGIN
    -- 1. 删除 indicator_id (冗余，与 id 重复)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='indicator_id') THEN
        ALTER TABLE public.indicator DROP COLUMN indicator_id;
        RAISE NOTICE '已删除 indicator_id 字段';
    END IF;

    -- 2. 删除 is_qualitative (冗余，通过 type='定性' 来判断)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='is_qualitative') THEN
        ALTER TABLE public.indicator DROP COLUMN is_qualitative;
        RAISE NOTICE '已删除 is_qualitative 字段';
    END IF;

    -- 3. 删除 pending_attachments (附件应存储在关联表)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='pending_attachments') THEN
        ALTER TABLE public.indicator DROP COLUMN pending_attachments;
        RAISE NOTICE '已删除 pending_attachments 字段';
    END IF;

    -- 4. 删除 pending_progress (进度应在审批流程中)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='pending_progress') THEN
        ALTER TABLE public.indicator DROP COLUMN pending_progress;
        RAISE NOTICE '已删除 pending_progress 字段';
    END IF;

    -- 5. 删除 pending_remark (备注应在审批流程中)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='pending_remark') THEN
        ALTER TABLE public.indicator DROP COLUMN pending_remark;
        RAISE NOTICE '已删除 pending_remark 字段';
    END IF;

    -- 6. 删除 progress_approval_status (审批状态属于审批流程)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='progress_approval_status') THEN
        ALTER TABLE public.indicator DROP COLUMN progress_approval_status;
        RAISE NOTICE '已删除 progress_approval_status 字段';
    END IF;

    -- 7. 删除 can_withdraw (允许自由撤回，不锁死)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='can_withdraw') THEN
        ALTER TABLE public.indicator DROP COLUMN can_withdraw;
        RAISE NOTICE '已删除 can_withdraw 字段';
    END IF;

    -- 8. 删除 status_audit (JSONB 冗余)
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='indicator' AND column_name='status_audit') THEN
        ALTER TABLE public.indicator DROP COLUMN status_audit;
        RAISE NOTICE '已删除 status_audit 字段';
    END IF;

    RAISE NOTICE '====================================================';
    RAISE NOTICE 'V33 字段清理完成！';
    RAISE NOTICE '====================================================';
END $$;
