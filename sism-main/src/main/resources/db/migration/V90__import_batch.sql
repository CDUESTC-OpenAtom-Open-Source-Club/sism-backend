-- =====================================================
-- P6 Excel 导入：批次留痕表（谁/什么文件/什么时间）
-- =====================================================
CREATE TABLE IF NOT EXISTS public.import_batch (
    id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    import_type VARCHAR(32) NOT NULL,
    file_name VARCHAR(255),
    operator_user_id BIGINT,
    operator_org_id BIGINT,
    target_org_id BIGINT,
    cycle_id BIGINT,
    total_rows INTEGER DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_import_batch_batch_id ON public.import_batch (batch_id);
