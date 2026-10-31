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
import org.springframework.security.access.AccessDeniedException;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审核进度评定门禁回归（2026-10-30 P0 鉴定死锁 + 2026-10-31 定稿口径）。
 *
 * <p>2026-10-30：状态词表断层修复（上报侧 SUBMITTED / 实例侧 IN_REVIEW）。
 * <p>2026-10-31 定稿：可评定 = 上报审批期间 + 具备审批席角色 + 当前活动节点候选审批人；
 * 原「步骤白名单」（仅固定 step_order 可评）删除；lockHint 三分：
 * ①非上报期间 ②无席位角色 ③非当前环节评定人（含节点名）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Indicator Appraisal Application Service Tests")
class IndicatorAppraisalApplicationServiceTest {

    private static final long REPORT_ID = 2L;
    private static final long INDICATOR_ID = 2046L;
    private static final long INSTANCE_ID = 5L;
    private static final long APPROVER_USER_ID = 302L;

    /** 任一审批席角色即可进入「当前节点候选人」判定（原因②的放行面） */
    private static final List<String> SEAT_ROLES = List.of("ROLE_APPROVER");

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
    @DisplayName("上报在途 + 有席位角色 + 当前节点候选人 → 可评级")
    void shouldAllowAppraisalWhenReportSubmittedAndInstanceInReview() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        AuditInstance instance = stubInstance(AuditInstance.STATUS_PENDING, 3, 13L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(collegeFlow());
        when(approverResolver.canUserApprove(any(AuditStepDef.class), any(Long.class), any(AuditInstance.class)))
                .thenReturn(true);

        List<IndicatorAppraisalContextResponse> contexts =
                service().getContexts(List.of(INDICATOR_ID), APPROVER_USER_ID, SEAT_ROLES);

        assertEquals(1, contexts.size());
        IndicatorAppraisalContextResponse context = contexts.get(0);
        assertTrue(context.isCanAppraise(), "审批在途且本人是当前节点负责人 → 应可评级");
        assertEquals("分管职能部门负责人审批", context.getCurrentStepName());
        assertNotNull(instance);
    }

    @Test
    @DisplayName("白名单外节点的候选审批人也可评级（2026-10-31 定稿：删步骤白名单）")
    void shouldAllowAppraisalAtStepOutsideLegacyWhitelist() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        stubInstance(AuditInstance.STATUS_PENDING, 2, 23L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(funcDeptFlow());
        when(approverResolver.canUserApprove(any(AuditStepDef.class), any(Long.class), any(AuditInstance.class)))
                .thenReturn(true);

        IndicatorAppraisalContextResponse context =
                service().getContexts(List.of(INDICATOR_ID), APPROVER_USER_ID, SEAT_ROLES).get(0);

        assertTrue(context.isCanAppraise(),
                "旧白名单只允许 FUNCDEPT 第 4 步，现候选审批人在任一在途节点都可评，实际：" + context.getLockHint());
    }

    @Test
    @DisplayName("上报在途但有席位角色非当前节点候选人 → 拒绝且提示含节点名（原因③）")
    void shouldRejectAppraisalWithStepNameWhenNotCurrentStepApprover() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        stubInstance(AuditInstance.STATUS_PENDING, 3, 13L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(collegeFlow());
        when(approverResolver.canUserApprove(any(AuditStepDef.class), any(Long.class), any(AuditInstance.class)))
                .thenReturn(false);

        List<IndicatorAppraisalContextResponse> contexts =
                service().getContexts(List.of(INDICATOR_ID), APPROVER_USER_ID, SEAT_ROLES);

        assertEquals(1, contexts.size());
        IndicatorAppraisalContextResponse context = contexts.get(0);
        assertFalse(context.isCanAppraise());
        assertTrue(context.getLockHint().contains("您不是当前审批环节"),
                "非当前环节评定人应有独立提示，实际：" + context.getLockHint());
        assertTrue(context.getLockHint().contains("分管职能部门负责人审批"),
                "原因③必须带节点名，实际：" + context.getLockHint());
    }

    @Test
    @DisplayName("无任一审批席角色 → 拒绝且不再进入节点级判定（原因②）")
    void shouldRejectAppraisalWithoutAnySeatRole() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        stubInstance(AuditInstance.STATUS_PENDING, 3, 13L);

        List<IndicatorAppraisalContextResponse> contexts =
                service().getContexts(List.of(INDICATOR_ID), APPROVER_USER_ID, List.of());

        assertEquals(1, contexts.size());
        IndicatorAppraisalContextResponse context = contexts.get(0);
        assertFalse(context.isCanAppraise());
        assertEquals("当前角色无评定权限，无法评定审核进度", context.getLockHint());
        verifyNoInteractions(approverResolver, workflowDefinitionQueryService);
    }

    @Test
    @DisplayName("审批实例已 APPROVED → 不可评级：①非上报期间提示优先于角色判定，且与原因③可区分")
    void shouldRejectAppraisalWhenInstanceAlreadyApproved() {
        stubReportRow("SUBMITTED", "APPROVED");
        stubInstance(AuditInstance.STATUS_APPROVED, 3, 13L);

        List<IndicatorAppraisalContextResponse> contexts =
                service().getContexts(List.of(INDICATOR_ID), APPROVER_USER_ID, SEAT_ROLES);

        assertEquals(1, contexts.size());
        IndicatorAppraisalContextResponse context = contexts.get(0);
        assertFalse(context.isCanAppraise());
        assertTrue(context.getLockHint().contains("审批已结束"),
                "审批已结束应有独立提示，实际：" + context.getLockHint());
        assertFalse(context.getLockHint().contains("评定人"),
                "「审批已结束」不应与「非当前环节评定人」混用同一句提示");
    }

    @Test
    @DisplayName("setLevel：上报在途 + 席位角色 + 当前节点候选人 → 成功落库")
    void shouldPersistAppraisalLevelWhenReportSubmittedAndInstanceInReview() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        stubInstance(AuditInstance.STATUS_PENDING, 3, 13L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(collegeFlow());
        when(approverResolver.canUserApprove(any(AuditStepDef.class), any(Long.class), any(AuditInstance.class)))
                .thenReturn(true);

        IndicatorAppraisalContextResponse context =
                service().setLevel(INDICATOR_ID, "normal", APPROVER_USER_ID, SEAT_ROLES);

        verify(planReportIndicatorRepository).updateAppraisalLevel(REPORT_ID, INDICATOR_ID, "NORMAL");
        assertTrue(context.isCanAppraise());
    }

    @Test
    @DisplayName("setLevel：非当前节点候选人 → AccessDenied 且信息与 context 的 lockHint 同源（含节点名）")
    void shouldDenySetLevelWithStepNameWhenNotCurrentStepApprover() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        stubInstance(AuditInstance.STATUS_PENDING, 3, 13L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(collegeFlow());
        when(approverResolver.canUserApprove(any(AuditStepDef.class), any(Long.class), any(AuditInstance.class)))
                .thenReturn(false);

        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> service().setLevel(INDICATOR_ID, "NORMAL", APPROVER_USER_ID, SEAT_ROLES));

        assertTrue(exception.getMessage().contains("分管职能部门负责人审批"),
                "写入拒绝必须报出当前节点名，实际：" + exception.getMessage());
        verify(planReportIndicatorRepository, org.mockito.Mockito.never())
                .updateAppraisalLevel(any(Long.class), any(Long.class), anyString());
    }

    @Test
    @DisplayName("setLevel：无席位角色 → AccessDenied（原因②），即使候选人判定本可通过")
    void shouldDenySetLevelWithoutSeatRole() {
        stubReportRow("SUBMITTED", "IN_REVIEW");
        stubInstance(AuditInstance.STATUS_PENDING, 3, 13L);

        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> service().setLevel(INDICATOR_ID, "NORMAL", APPROVER_USER_ID, List.of()));

        assertEquals("当前角色无评定权限，无法评定审核进度", exception.getMessage());
        verifyNoInteractions(approverResolver, workflowDefinitionQueryService);
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

    /** 职能部门链：第 2 步在旧白名单（仅第 4 步）之外，用于验证白名单已删 */
    private AuditFlowDef funcDeptFlow() {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(4L);
        flowDef.setFlowCode("PLAN_APPROVAL_FUNCDEPT");

        AuditStepDef funcDeptHeadStep = new AuditStepDef();
        funcDeptHeadStep.setId(23L);
        funcDeptHeadStep.setStepOrder(2);
        funcDeptHeadStep.setStepName("职能部门审批人审批");
        funcDeptHeadStep.setRoleId(2L);

        AuditStepDef strategyStep = new AuditStepDef();
        strategyStep.setId(24L);
        strategyStep.setStepOrder(4);
        strategyStep.setStepName("战略发展部审批");
        strategyStep.setRoleId(3L);

        flowDef.setSteps(List.of(funcDeptHeadStep, strategyStep));
        return flowDef;
    }
}
