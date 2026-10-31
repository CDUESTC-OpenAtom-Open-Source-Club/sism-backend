-- =====================================================
-- 2026-10-30 缺陷修复：新功能「取消异动」把 audit_instance.status 置为 CANCELLED
-- （AuditInstance.STATUS_CANCELLED），运行时写库被 audit_instance_status_check 拒绝。
-- 本迁移把 CANCELLED 加入状态 CHECK 枚举，写法与 V82__allow_withdrawn_audit_instance_status.sql 一致。
-- =====================================================

ALTER TABLE public.audit_instance
    DROP CONSTRAINT IF EXISTS audit_instance_status_check;

ALTER TABLE public.audit_instance
    ADD CONSTRAINT audit_instance_status_check CHECK (
        (status)::text = ANY (
            ARRAY[
                ('IN_REVIEW'::character varying)::text,
                ('APPROVED'::character varying)::text,
                ('REJECTED'::character varying)::text,
                ('WITHDRAWN'::character varying)::text,
                ('CANCELLED'::character varying)::text
            ]
        )
    );

COMMENT ON COLUMN public.audit_instance.status IS '审批实例状态：IN_REVIEW / APPROVED / REJECTED / WITHDRAWN / CANCELLED';
