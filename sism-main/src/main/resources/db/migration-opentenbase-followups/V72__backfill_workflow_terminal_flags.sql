DO $$
BEGIN
    UPDATE public.audit_step_def AS asd
    SET is_terminal = TRUE,
        updated_at = CURRENT_TIMESTAMP
    WHERE COALESCE(asd.is_terminal, FALSE) = FALSE
      AND COALESCE(UPPER(asd.step_type), '') = 'APPROVAL'
      AND EXISTS (
            SELECT 1
            FROM public.audit_flow_def afd
            WHERE afd.id = asd.flow_id
              AND afd.flow_code = 'PLAN_APPROVAL_COLLEGE'
      )
      AND (
            asd.step_name = '职能部门终审人审批'
            OR NOT EXISTS (
                SELECT 1
                FROM public.audit_step_def next_step
                WHERE next_step.flow_id = asd.flow_id
                  AND COALESCE(next_step.step_no, 0) > COALESCE(asd.step_no, 0)
            )
      );
END
$$;
