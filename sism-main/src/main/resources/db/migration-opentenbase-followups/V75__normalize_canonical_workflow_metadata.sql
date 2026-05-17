DO $$
BEGIN
    UPDATE public.audit_step_def AS asd
    SET step_type = CASE
                        WHEN COALESCE(asd.step_no, 0) = 1 THEN 'SUBMIT'
                        ELSE 'APPROVAL'
                    END,
        role_id = CASE
                      WHEN COALESCE(asd.step_no, 0) = 1 THEN NULL
                      ELSE asd.role_id
                  END,
        is_terminal = CASE
                          WHEN COALESCE(asd.step_no, 0) = (
                              SELECT MAX(last_step.step_no)
                              FROM public.audit_step_def last_step
                              WHERE last_step.flow_id = asd.flow_id
                          )
                          AND COALESCE(asd.step_no, 0) > 1
                          THEN TRUE
                          ELSE FALSE
                      END,
        updated_at = CURRENT_TIMESTAMP
    WHERE EXISTS (
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
END
$$;
