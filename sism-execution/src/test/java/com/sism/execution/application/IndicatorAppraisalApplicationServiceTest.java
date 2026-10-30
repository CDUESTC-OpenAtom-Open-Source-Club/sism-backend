package com.sism.execution.application;

import com.sism.execution.domain.report.PlanReportIndicatorRepository;
import com.sism.execution.interfaces.dto.IndicatorAppraisalContextResponse;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-30 回归：P0 鉴定死锁（状态词表断层）。
 *
 * <p>旧实现拿 "IN_REVIEW" 去比 {@code plan_report.status}，而上报侧「已提交待审批」
 * 的权威值是 {@code SUBMITTED}（legacy 别名 IN_REVIEW 会被归一化成 SUBMITTED）；
 * 「在途」的权威值在审批实例侧（{@code audit_instance.status = IN_REVIEW}）。
 * 两者词表不同 → 「谁能评级」恒为 false → 可评级节点又强制「全部已评定」→ 结构性死锁。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Indicator Appraisal Application Service Tests")
class IndicatorAppraisalApplicationServiceTest {

    private static final long REPORT_ID = 2L;
    private static final long INDICATOR_ID = 2046L;
    private static final long INSTANCE_ID = 5L;
    private static final long APPROVER_USER_ID = 302L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlanReportIndicatorRepository planReportIndicatorRepository;

    @Mock
    private AuditInstanceRepository auditInstanceRepository;

    @Mock
    private WorkflowDefinitionQueryService workflowDefinitionQueryService;

    @Mock
    private ApproverResolver approverResolver;

    @Test
    @DisplayName("上报状态 SUBMITTED + 审批实例 IN_REVIEW + 当前节点负责人 → 可评级")
    void shouldAllowAppraisalWhenReportSubmittedAndInstanceInReview() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        AuditInstance instance = stubInstance(AuditInstance.STATUS_PENDING, 3, 13L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(collegeFlow());
        when(approverResolver.canUserApprove(any(AuditStepDef.class), any(Long.class), any(AuditInstance.class)))
                .thenReturn(true);

        List<IndicatorAppraisalContextResponse> contexts =
                service().getContexts(List.of(INDICATOR_ID), APPROVER_USER_ID);

        assertEquals(1, contexts.size());
        IndicatorAppraisalContextResponse context = contexts.get(0);
        assertTrue(context.isCanAppraise(), "审批在途且本人是当前节点负责人 → 应可评级");
        assertEquals("分管职能部门负责人审批", context.getCurrentStepName());
        assertNotNull(instance);
    }

    @Test
    @DisplayName("上报状态 SUBMITTED 但审批实例已 APPROVED → 不可评级，提示与「非本节点」可区分")
    void shouldRejectAppraisalWhenInstanceAlreadyApproved() {
        stubReportRow("SUBMITTED", "APPROVED");
        stubInstance(AuditInstance.STATUS_APPROVED, 3, 13L);

        List<IndicatorAppraisalContextResponse> contexts =
                service().getContexts(List.of(INDICATOR_ID), APPROVER_USER_ID);

        assertEquals(1, contexts.size());
        IndicatorAppraisalContextResponse context = contexts.get(0);
        assertFalse(context.isCanAppraise());
        assertTrue(context.getLockHint().contains("审批已结束"),
                "审批已结束应有独立提示，实际：" + context.getLockHint());
        assertFalse(context.getLockHint().contains("只审核"),
                "「审批已结束」不应与「本节点只审核」混用同一句提示");
    }

    @Test
    @DisplayName("审批在途但当前节点只审核 → 提示与「审批已结束」可区分")
    void shouldRejectAppraisalWhenCurrentStepCannotAppraise() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        stubInstance(AuditInstance.STATUS_PENDING, 4, 14L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(collegeFlow());

        List<IndicatorAppraisalContextResponse> contexts =
                service().getContexts(List.of(INDICATOR_ID), APPROVER_USER_ID);

        assertEquals(1, contexts.size());
        IndicatorAppraisalContextResponse context = contexts.get(0);
        assertFalse(context.isCanAppraise());
        assertTrue(context.getLockHint().contains("只审核"),
                "本节点只审核应有独立提示，实际：" + context.getLockHint());
    }

    @Test
    @DisplayName("setLevel 在 SUBMITTED + 实例 IN_REVIEW 时能成功落库（本次修复的核心断言）")
    void shouldPersistAppraisalLevelWhenReportSubmittedAndInstanceInReview() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        stubInstance(AuditInstance.STATUS_PENDING, 3, 13L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(collegeFlow());
        when(approverResolver.canUserApprove(any(AuditStepDef.class), any(Long.class), any(AuditInstance.class)))
                .thenReturn(true);

        IndicatorAppraisalContextResponse context =
                service().setLevel(INDICATOR_ID, "normal", APPROVER_USER_ID);

        verify(planReportIndicatorRepository).updateAppraisalLevel(REPORT_ID, INDICATOR_ID, "NORMAL");
        assertTrue(context.isCanAppraise());
    }

    private IndicatorAppraisalApplicationService service() {
        return new IndicatorAppraisalApplicationService(
                jdbcTemplate,
                planReportIndicatorRepository,
                auditInstanceRepository,
                workflowDefinitionQueryService,
                approverResolver,
                List.of()
        );
    }

    /** 模拟 plan_report + audit_instance 的联表结果 */
    private void stubReportRow(String reportStatus, String instanceStatus) {
        try {
            doAnswer(invocation -> {
                        RowCallbackHandler handler = invocation.getArgument(1);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getLong("indicator_id")).thenReturn(INDICATOR_ID);
                        when(rs.getLong("report_id")).thenReturn(REPORT_ID);
                        when(rs.getString("report_month")).thenReturn("202610");
                        when(rs.getString("status")).thenReturn(reportStatus);
                        when(rs.getObject("audit_instance_id")).thenReturn(INSTANCE_ID);
                        when(rs.getLong("audit_instance_id")).thenReturn(INSTANCE_ID);
                        when(rs.getString("appraisal_level")).thenReturn(null);
                        when(rs.getString("instance_status")).thenReturn(instanceStatus);
                        handler.processRow(rs);
                        return null;
                    })
                    .when(jdbcTemplate)
                    .query(
                            anyString(),
                            org.mockito.ArgumentMatchers.<RowCallbackHandler>any(),
                            any(Object[].class));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AuditInstance stubInstance(String status, int stepNo, Long stepDefId) {
        AuditInstance instance = new AuditInstance();
        instance.setId(INSTANCE_ID);
        instance.setFlowDefId(4L);
        instance.setEntityType("PLAN_REPORT");
        instance.setEntityId(REPORT_ID);
        instance.setStatus(status);
        instance.setRequesterOrgId(57L);

        AuditStepInstance step = new AuditStepInstance();
        step.setId(17L);
        step.setStepNo(stepNo);
        step.setStepDefId(stepDefId);
        step.setStepName("分管职能部门负责人审批");
        step.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(step);

        when(auditInstanceRepository.findById(INSTANCE_ID)).thenReturn(Optional.of(instance));
        return instance;
    }

    private AuditFlowDef collegeFlow() {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(4L);
        flowDef.setFlowCode("PLAN_APPROVAL_COLLEGE");

        AuditStepDef functionalHeadStep = new AuditStepDef();
        functionalHeadStep.setId(13L);
        functionalHeadStep.setStepOrder(3);
        functionalHeadStep.setStepName("分管职能部门负责人审批");
        functionalHeadStep.setRoleId(2L);

        AuditStepDef functionalVpStep = new AuditStepDef();
        functionalVpStep.setId(14L);
        functionalVpStep.setStepOrder(4);
        functionalVpStep.setStepName("分管职能部门校领导审批");
        functionalVpStep.setRoleId(4L);

        flowDef.setSteps(List.of(functionalHeadStep, functionalVpStep));
        return flowDef;
    }
}
