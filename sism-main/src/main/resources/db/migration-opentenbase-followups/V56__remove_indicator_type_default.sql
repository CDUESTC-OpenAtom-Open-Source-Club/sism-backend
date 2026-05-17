-- Remove the fallback default for indicator.type.
-- Indicator type must always be supplied explicitly by application code.

ALTER TABLE public.indicator
    ALTER COLUMN type DROP DEFAULT;

COMMENT ON COLUMN public.indicator.type IS '指标类型: 定量/定性（必须显式传入，不允许默认值）';
