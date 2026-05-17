-- V2__template_for_future_changes.sql
-- Purpose:
-- - Template for future schema changes after the new V1 baseline.
-- - Prefer not to change database structure unless the customer explicitly requires it.
--
-- Rules:
-- 1. Do not edit V1__baseline_current_schema.sql
-- 2. Keep this file as a copy template; rename it before real use
-- 3. Prefer idempotent DDL with IF EXISTS / IF NOT EXISTS
-- 4. If the requirement can be solved without schema changes, do not create a migration

-- Example:
-- ALTER TABLE public.example_table
--     ADD COLUMN IF NOT EXISTS example_column BIGINT;

-- Example rollback note:
-- If rollback is needed, create a new forward migration instead of editing an old one.
