package com.sism.strategy.application;

import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
class PlanWorkflowRuntimeService {

    private static final String ROLE_CODE_APPROVER = "APPROVER";
    private static final String ROLE_CODE_STRATEGY_DEPT_HEAD = "STRATEGY_DEPT_HEAD";
    private static final String ROLE_CODE_VICE_PRESIDENT = "VICE_PRESIDENT";

    private final JdbcTemplate jdbcTemplate;
    private final PlanRepository planRepository;
    private final StrategyOrgProperties strategyOrgProperties;

    void withdrawWorkflowCurrentStep(Long workflowInstanceId) {
        if (workflowInstanceId == null) {
            return;
        }

        List<WorkflowStepRow> submitterSteps = jdbcTemplate.query(
                """
                SELECT asi.id, asi.step_no
                FROM public.audit_step_instance asi
                JOIN public.audit_instance ai ON ai.id = asi.instance_id
                LEFT JOIN public.audit_step_def asd ON asd.id = asi.step_def_id
                WHERE asi.instance_id = ?
                  AND asi.status = 'APPROVED'
                  AND (
                        COALESCE(UPPER(asd.step_type), '') = 'SUBMIT'
                        OR (ai.requester_id IS NOT NULL AND ai.requester_id = asi.approver_id)
                        OR asi.step_name LIKE '%提交%'
                  )
                ORDER BY asi.step_no DESC NULLS LAST, asi.approved_at DESC NULLS LAST, asi.id DESC
                LIMIT 1
                """,
                (rs, _rowNum) -> new WorkflowStepRow(rs.getLong(1), rs.getInt(2), null, true, null, null),
                workflowInstanceId
        );

        if (!submitterSteps.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE public.audit_step_instance
                    SET status = 'WITHDRAWN',
                        approved_at = CURRENT_TIMESTAMP,
                        comment = '提交人撤回'
                    WHERE id = ?
                    """, submitterSteps.get(0).id());
        }

        jdbcTemplate.update("""
                UPDATE public.audit_step_instance
                SET status = 'WAITING'
                WHERE instance_id = ?
                  AND status = 'PENDING'
                  AND step_no > 1
                """, workflowInstanceId);

        jdbcTemplate.update("""
                UPDATE public.audit_instance
                SET status = 'WITHDRAWN',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, workflowInstanceId);
    }

    boolean reactivateWithdrawnWorkflowCurrentStep(Long workflowInstanceId, String submitComment) {
        if (workflowInstanceId == null) {
            return false;
        }

        List<WorkflowStepRow> withdrawnSteps = jdbcTemplate.query(
                """
                SELECT asi.id,
                       asi.step_no,
                       asi.step_def_id,
                       COALESCE(UPPER(asd.step_type), '') = 'SUBMIT' AS is_submit,
                       ai.requester_id,
                       ai.requester_org_id
                FROM public.audit_step_instance asi
                JOIN public.audit_instance ai ON ai.id = asi.instance_id
                LEFT JOIN public.audit_step_def asd ON asd.id = asi.step_def_id
                WHERE asi.instance_id = ?
                  AND asi.status = 'WITHDRAWN'
                ORDER BY asi.step_no DESC NULLS LAST, asi.id DESC
                LIMIT 1
                """,
                (rs, _rowNum) -> new WorkflowStepRow(
                        rs.getLong(1),
                        rs.getInt(2),
                        rs.getLong(3),
                        rs.getBoolean(4),
                        rs.getLong(5),
                        rs.getLong(6)
                ),
                workflowInstanceId
        );

        if (withdrawnSteps.isEmpty()) {
            return false;
        }

        WorkflowStepRow withdrawnStep = withdrawnSteps.get(0);
        WorkflowInstanceContext workflowContext = loadWorkflowInstanceContext(workflowInstanceId);
        if (workflowContext == null) {
            log.debug("Workflow instance context missing for {}, fallback to withdrawn step context", workflowInstanceId);
            workflowContext = new WorkflowInstanceContext(
                    workflowInstanceId,
                    null,
                    withdrawnStep.requesterId(),
                    withdrawnStep.requesterOrgId(),
                    "PLAN",
                    null
            );
        }
        if (withdrawnStep.isSubmitStep()) {
            String resolvedSubmitComment = normalizeSubmitComment(submitComment);
            jdbcTemplate.update("""
                    UPDATE public.audit_step_instance
                    SET status = 'APPROVED',
                        approved_at = CURRENT_TIMESTAMP,
                        comment = ?
                    WHERE id = ?
                    """, resolvedSubmitComment, withdrawnStep.id());

            if (workflowContext.flowDefId() == null) {
                if (withdrawnStep.stepNo() <= 1) {
                    jdbcTemplate.update("""
                            UPDATE public.audit_step_instance
                            SET status = 'PENDING',
                                approved_at = NULL,
                                comment = NULL
                            WHERE instance_id = ?
                              AND status = 'WAITING'
                            """, workflowInstanceId);
                } else {
                    String insertSql = """
                            INSERT INTO public.audit_step_instance (
                                step_def_id,
                                instance_id,
                                step_no,
                                status,
                                created_at
                            )
                            VALUES (?, ?, %d, 'PENDING', CURRENT_TIMESTAMP)
                            """.formatted(withdrawnStep.stepNo() + 1);
                    jdbcTemplate.update(insertSql, withdrawnStep.stepDefId(), workflowInstanceId);
                }

                jdbcTemplate.update("""
                        UPDATE public.audit_instance
                        SET status = 'IN_REVIEW',
                            completed_at = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, workflowInstanceId);
                return true;
            }

            WorkflowStepRow waitingStep = loadFirstWaitingWorkflowStep(workflowInstanceId);
            int restoredWaitingRows = 0;
            if (waitingStep != null) {
                WorkflowStepDefinition waitingStepDef = loadWorkflowStepDefinition(waitingStep.stepDefId());
                ResolvedWorkflowApprover resolvedApprover = resolveWorkflowApprover(waitingStepDef, workflowContext);
                restoredWaitingRows = jdbcTemplate.update("""
                        UPDATE public.audit_step_instance
                        SET status = 'PENDING',
                            approver_id = ?,
                            approver_org_id = ?,
                            approved_at = NULL,
                            comment = NULL
                        WHERE id = ?
                        """,
                        resolvedApprover.approverId(),
                        resolvedApprover.approverOrgId(),
                        waitingStep.id());
            }

            if (restoredWaitingRows == 0) {
                WorkflowStepDefinition nextStepDef = loadNextWorkflowStepDefinition(
                        workflowContext.flowDefId(),
                        withdrawnStep.stepDefId()
                );
                if (nextStepDef != null) {
                    ResolvedWorkflowApprover resolvedApprover = resolveWorkflowApprover(nextStepDef, workflowContext);
                    jdbcTemplate.update("""
                            INSERT INTO public.audit_step_instance (
                                instance_id,
                                step_no,
                                step_name,
                                step_def_id,
                                status,
                                approver_id,
                                approver_org_id,
                                comment,
                                approved_at,
                                created_at
                            )
                            VALUES (?, ?, ?, ?, 'PENDING', ?, ?, NULL, NULL, CURRENT_TIMESTAMP)
                            """,
                            workflowInstanceId,
                            withdrawnStep.stepNo() + 1,
                            nextStepDef.stepName(),
                            nextStepDef.id(),
                            resolvedApprover.approverId(),
                            resolvedApprover.approverOrgId()
                    );
                }
            }
        } else {
            WorkflowStepDefinition returnedStepDef = loadWorkflowStepDefinition(withdrawnStep.stepDefId());
            ResolvedWorkflowApprover resolvedApprover = resolveWorkflowApprover(returnedStepDef, workflowContext);
            jdbcTemplate.update("""
                    UPDATE public.audit_step_instance
                    SET status = 'PENDING',
                        approver_id = ?,
                        approver_org_id = ?,
                        approved_at = NULL,
                        comment = NULL
                    WHERE id = ?
                    """,
                    resolvedApprover.approverId(),
                    resolvedApprover.approverOrgId(),
                    withdrawnStep.id());
        }
        jdbcTemplate.update("""
                UPDATE public.audit_instance
                SET status = 'IN_REVIEW',
                    completed_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, workflowInstanceId);
        return true;
    }

    private String normalizeSubmitComment(String submitComment) {
        if (submitComment != null && !submitComment.trim().isEmpty()) {
            return submitComment.trim();
        }
        return "系统自动完成提交流程节点";
    }

    private WorkflowInstanceContext loadWorkflowInstanceContext(Long workflowInstanceId) {
        List<WorkflowInstanceContext> rows = jdbcTemplate.query(
                """
                SELECT id, flow_def_id, requester_id, requester_org_id, entity_type, entity_id
                FROM public.audit_instance
                WHERE id = ?
                """,
                (rs, _rowNum) -> new WorkflowInstanceContext(
                        rs.getLong("id"),
                        rs.getLong("flow_def_id"),
                        rs.getLong("requester_id"),
                        rs.getLong("requester_org_id"),
                        rs.getString("entity_type"),
                        rs.getLong("entity_id")
                ),
                workflowInstanceId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WorkflowStepRow loadFirstWaitingWorkflowStep(Long workflowInstanceId) {
        List<WorkflowStepRow> rows = jdbcTemplate.query(
                """
                SELECT id, step_no, step_def_id
                FROM public.audit_step_instance
                WHERE instance_id = ?
                  AND status = 'WAITING'
                  AND step_no > 1
                ORDER BY step_no ASC, id ASC
                LIMIT 1
                """,
                (rs, _rowNum) -> new WorkflowStepRow(
                        rs.getLong("id"),
                        rs.getInt("step_no"),
                        rs.getLong("step_def_id"),
                        false,
                        null,
                        null
                ),
                workflowInstanceId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WorkflowStepDefinition loadWorkflowStepDefinition(Long stepDefId) {
        if (stepDefId == null) {
            return null;
        }
        List<WorkflowStepDefinition> rows = jdbcTemplate.query(
                """
                SELECT id, step_name, step_no, step_type, role_id
                FROM public.audit_step_def
                WHERE id = ?
                """,
                (rs, _rowNum) -> new WorkflowStepDefinition(
                        rs.getLong("id"),
                        rs.getString("step_name"),
                        rs.getInt("step_no"),
                        rs.getString("step_type"),
                        rs.getObject("role_id") == null ? null : rs.getLong("role_id")
                ),
                stepDefId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WorkflowStepDefinition loadNextWorkflowStepDefinition(Long flowDefId, Long currentStepDefId) {
        List<WorkflowStepDefinition> rows = jdbcTemplate.query(
                """
                SELECT next_def.id, next_def.step_name, next_def.step_no, next_def.step_type, next_def.role_id
                FROM public.audit_step_def current_def
                JOIN public.audit_step_def next_def
                  ON next_def.flow_id = current_def.flow_id
                 AND next_def.step_no = current_def.step_no + 1
                WHERE current_def.id = ?
                  AND current_def.flow_id = ?
                LIMIT 1
                """,
                (rs, _rowNum) -> new WorkflowStepDefinition(
                        rs.getLong("id"),
                        rs.getString("step_name"),
                        rs.getInt("step_no"),
                        rs.getString("step_type"),
                        rs.getObject("role_id") == null ? null : rs.getLong("role_id")
                ),
                currentStepDefId,
                flowDefId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ResolvedWorkflowApprover resolveWorkflowApprover(
            WorkflowStepDefinition stepDef,
            WorkflowInstanceContext context
    ) {
        if (stepDef == null) {
            return new ResolvedWorkflowApprover(null, context.requesterOrgId());
        }
        if (stepDef.isSubmitStep()) {
            return new ResolvedWorkflowApprover(context.requesterId(), context.requesterOrgId());
        }

        Long approverOrgId = resolveWorkflowApproverOrgId(stepDef, context);
        Long approverId = resolveWorkflowApproverId(stepDef, approverOrgId);
        return new ResolvedWorkflowApprover(approverId, approverOrgId);
    }

    private Long resolveWorkflowApproverOrgId(
            WorkflowStepDefinition stepDef,
            WorkflowInstanceContext context
    ) {
        String roleCode = loadRoleCode(stepDef.roleId());
        if (ROLE_CODE_STRATEGY_DEPT_HEAD.equals(roleCode)) {
            return strategyOrgProperties.getStrategyOrgId();
        }
        if (ROLE_CODE_VICE_PRESIDENT.equals(roleCode)) {
            return context.requesterOrgId();
        }
        if (ROLE_CODE_APPROVER.equals(roleCode) && isCollegeFinalApprovalStep(stepDef)) {
            return planRepository.findById(context.entityId())
                    .map(Plan::getCreatedByOrgId)
                    .orElse(context.requesterOrgId());
        }
        return context.requesterOrgId();
    }

    private boolean isCollegeFinalApprovalStep(WorkflowStepDefinition stepDef) {
        return stepDef != null
                && stepDef.stepName() != null
                && stepDef.stepName().contains("职能部门终审");
    }

    private String loadRoleCode(Long roleId) {
        if (roleId == null) {
            return null;
        }
        List<String> roleCodes = jdbcTemplate.query(
                """
                SELECT role_code
                FROM public.sys_role
                WHERE id = ?
                LIMIT 1
                """,
                (rs, _rowNum) -> rs.getString(1),
                roleId
        );
        return roleCodes.isEmpty() ? null : roleCodes.get(0);
    }

    private Long resolveWorkflowApproverId(WorkflowStepDefinition stepDef, Long approverOrgId) {
        if (stepDef.roleId() == null || approverOrgId == null) {
            return null;
        }
        List<Long> approverIds = jdbcTemplate.query(
                """
                SELECT u.id
                FROM public.sys_user u
                JOIN public.sys_user_role ur ON ur.user_id = u.id
                WHERE ur.role_id = ?
                  AND u.org_id = ?
                  AND COALESCE(u.is_active, false) = true
                ORDER BY u.id ASC
                """,
                (rs, _rowNum) -> rs.getLong(1),
                stepDef.roleId(),
                approverOrgId
        );
        return approverIds.isEmpty() ? null : approverIds.get(0);
    }

    private record WorkflowStepRow(
            Long id,
            Integer stepNo,
            Long stepDefId,
            boolean isSubmitStep,
            Long requesterId,
            Long requesterOrgId
    ) {}

    private record WorkflowInstanceContext(
            Long id,
            Long flowDefId,
            Long requesterId,
            Long requesterOrgId,
            String entityType,
            Long entityId
    ) {}

    private record ResolvedWorkflowApprover(Long approverId, Long approverOrgId) {}

    private record WorkflowStepDefinition(
            Long id,
            String stepName,
            Integer stepOrder,
            String stepType,
            Long roleId
    ) {
        private boolean isSubmitStep() {
            return "SUBMIT".equalsIgnoreCase(String.valueOf(stepType));
        }
    }
}
