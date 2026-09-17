-- Validate canonical workflow template metadata without changing data.
-- Expected invariants for the four approved PLAN workflows:
-- 1. First step must be SUBMIT
-- 2. SUBMIT steps must not keep role_id
-- 3. Exactly one terminal approval step exists per flow

WITH canonical_flows AS (
    SELECT id, flow_code
    FROM public.audit_flow_def
    WHERE flow_code IN (
        'PLAN_DISPATCH_STRATEGY',
        'PLAN_DISPATCH_FUNCDEPT',
        'PLAN_APPROVAL_FUNCDEPT',
        'PLAN_APPROVAL_COLLEGE',
        'PLAN_MUTATION_STRATEGY'
    )
),
ordered_steps AS (
    SELECT afd.flow_code,
           asd.step_no,
           asd.step_name,
           asd.step_type,
           asd.role_id,
           COALESCE(asd.is_terminal, FALSE) AS is_terminal
    FROM canonical_flows afd
    JOIN public.audit_step_def asd ON asd.flow_id = afd.id
)
SELECT flow_code,
       COUNT(*) FILTER (WHERE step_no = 1 AND COALESCE(UPPER(step_type), '') = 'SUBMIT') AS submit_first_step_count,
       COUNT(*) FILTER (WHERE COALESCE(UPPER(step_type), '') = 'SUBMIT' AND role_id IS NOT NULL) AS submit_steps_with_role_count,
       COUNT(*) FILTER (WHERE COALESCE(UPPER(step_type), '') = 'APPROVAL' AND is_terminal) AS terminal_approval_count
FROM ordered_steps
GROUP BY flow_code
ORDER BY flow_code;
