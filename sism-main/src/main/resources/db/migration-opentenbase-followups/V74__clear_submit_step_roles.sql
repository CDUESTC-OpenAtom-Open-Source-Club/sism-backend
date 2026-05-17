DO $$
BEGIN
    UPDATE public.audit_step_def AS asd
    SET role_id = NULL,
        updated_at = CURRENT_TIMESTAMP
    WHERE asd.role_id IS NOT NULL
      AND COALESCE(UPPER(asd.step_type), '') = 'SUBMIT'
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
END
$$;
