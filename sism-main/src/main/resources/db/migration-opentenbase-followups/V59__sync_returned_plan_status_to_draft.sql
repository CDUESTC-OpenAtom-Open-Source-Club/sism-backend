-- Align plan business status with workflow runtime state.
-- Business rule:
-- If a PLAN workflow has already returned/withdrawn back to the requester submit step,
-- the related plan must return to DRAFT so the requester can edit and resubmit.

WITH returned_plan_instances AS (
    SELECT DISTINCT ai.entity_id AS plan_id
    FROM audit_instance ai
    JOIN audit_step_instance asi ON asi.instance_id = ai.id
    WHERE ai.entity_type = 'PLAN'
      AND ai.entity_id IS NOT NULL
      AND (
          ai.status = 'WITHDRAWN'
          OR (
              ai.status IN ('IN_REVIEW', 'RETURNED')
              AND asi.status = 'WITHDRAWN'
              AND asi.step_name LIKE '%提交%'
          )
      )
)
UPDATE public.plan p
SET status = 'DRAFT',
    updated_at = CURRENT_TIMESTAMP
FROM returned_plan_instances rpi
WHERE p.id = rpi.plan_id
  AND p.status <> 'DRAFT';
