-- Remove replay approval steps that were created too early after a reject-to-submit flow.
-- Correct behavior:
-- 1. Reject current approval step
-- 2. Append returned submit step only
-- 3. Wait for requester resubmission
-- 4. Create the next approval step only after resubmission
--
-- Historical bug:
-- The system appended a WAITING replay step immediately after the returned submit step.
-- This migration removes those premature WAITING steps so the database matches the intended workflow state.

DELETE FROM audit_step_instance waiting_step
USING audit_instance instance_row,
      audit_step_instance withdrawn_submit_step
WHERE waiting_step.instance_id = instance_row.id
  AND withdrawn_submit_step.instance_id = instance_row.id
  AND waiting_step.status = 'WAITING'
  AND withdrawn_submit_step.status = 'WITHDRAWN'
  AND withdrawn_submit_step.step_name LIKE '%提交%'
  AND COALESCE(waiting_step.step_no, 0) > COALESCE(withdrawn_submit_step.step_no, 0)
  AND NOT EXISTS (
      SELECT 1
      FROM audit_step_instance resumed_submit_step
      WHERE resumed_submit_step.instance_id = instance_row.id
        AND resumed_submit_step.step_name LIKE '%提交%'
        AND resumed_submit_step.status = 'APPROVED'
        AND COALESCE(resumed_submit_step.step_no, 0) > COALESCE(withdrawn_submit_step.step_no, 0)
  );
