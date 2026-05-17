DO $$
BEGIN
    UPDATE public.audit_step_def AS asd
    SET step_type = CASE
                        WHEN COALESCE(asd.step_no, 0) = 1 THEN 'SUBMIT'
                        ELSE 'APPROVAL'
                    END,
        updated_at = CURRENT_TIMESTAMP
    WHERE (asd.step_type IS NULL OR BTRIM(asd.step_type) = '')
      AND EXISTS (
            SELECT 1
            FROM public.audit_flow_def afd
            WHERE afd.id = asd.flow_id
              AND afd.flow_code IN (
                    'PLAN_DISPATCH_STRATEGY',
                    'PLAN_DISPATCH_FUNCDEPT',
                    'PLAN_APPROVAL_FUNCDEPT',
                    'PLAN_APPROVAL_COLLEGE'
              )
      );

    UPDATE public.audit_step_def AS asd
    SET is_terminal = TRUE,
        updated_at = CURRENT_TIMESTAMP
    WHERE COALESCE(asd.is_terminal, FALSE) = FALSE
      AND COALESCE(UPPER(asd.step_type), '') = 'APPROVAL'
      AND EXISTS (
            SELECT 1
            FROM public.audit_flow_def afd
            WHERE afd.id = asd.flow_id
              AND afd.flow_code IN (
                    'PLAN_DISPATCH_STRATEGY',
                    'PLAN_DISPATCH_FUNCDEPT',
                    'PLAN_APPROVAL_FUNCDEPT',
                    'PLAN_APPROVAL_COLLEGE'
              )
      )
      AND NOT EXISTS (
            SELECT 1
            FROM public.audit_step_def next_step
            WHERE next_step.flow_id = asd.flow_id
              AND COALESCE(next_step.step_no, 0) > COALESCE(asd.step_no, 0)
      );
END
$$;
