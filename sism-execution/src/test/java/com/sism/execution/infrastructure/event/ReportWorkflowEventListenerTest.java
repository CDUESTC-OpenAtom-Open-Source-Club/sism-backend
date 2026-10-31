package com.sism.execution.infrastructure.event;

import com.sism.execution.application.ReportApplicationService;
import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.ReportOrgType;
import com.sism.execution.domain.report.event.PlanReportSubmittedEvent;
import com.sism.iam.application.service.UserNotificationService;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.domain.integration.DingTalkTodoProvider;
import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.application.WorkflowApplicationService;
import com.sism.workflow.application.WorkflowTerminalStatusSyncService;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.query.WorkflowReadModelService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.interfaces.dto.ApproverCandidateResponse;
import com.sism.workflow.interfaces.dto.StartWorkflowRequest;
import com.sism.workflow.interfaces.dto.WorkflowInstanceDetailResponse;
import com.sism.workflow.interfaces.dto.WorkflowInstanceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Report workflow DingTalk push department direction tests")
class ReportWorkflowEventListenerTest {

    @Mock
    private BusinessWorkflowApplicationService businessWorkflowService;
    @Mock
    private WorkflowApplicationService workflowApplicationService;
    @Mock
    private AuditInstanceRepository auditInstanceRepository;
    @Mock
    private WorkflowTerminalStatusSyncService workflowTerminalStatusSyncService;
    @Mock
    private ReportApplicationService reportApplicationService;
    @Mock
    private WorkflowDefinitionQueryService workflowDefinitionQueryService;
    @Mock
    private WorkflowReadModelService workflowReadModelService;
    @Mock
    private ApproverResolver approverResolver;
    @Mock
    private UserNotificationService userNotificationService;
    @Mock
    private DingTalkTodoProvider dingTalkTodoProvider;
    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private ReportWorkflowEventListener listener;

    @Test
    @DisplayName("2026-10-30 修复：上报链钉钉推送 departmentName=填报组织（reportOrgId），不再是下发方向组织")
    void pushApprovalTodo_shouldUseReportOrgAsDepartmentName() {
        PlanReport report = new PlanReport();
        report.setId(701L);
        report.setPlanId(3L);
        report.setReportMonth("202610");
        report.setReportOrgId(44L);
        report.setReportOrgType(ReportOrgType.FUNC_DEPT);
        report.setTitle("202610 教务处月报");

        SysOrg reportOrg = new SysOrg();
        reportOrg.setName("教务处");

        WorkflowInstanceResponse started = new WorkflowInstanceResponse();
        started.setInstanceId("91");

        AuditStepDef approvalStep = new AuditStepDef();
        approvalStep.setId(8L);
        approvalStep.setStepName("职能部门审批人审批");
        approvalStep.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        approvalStep.setStepOrder(2);

        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setSteps(List.of(approvalStep));

        // detail.sourceOrgName 是下发方向（plan.createdByOrgId=战略发展部），不是提交方
        WorkflowInstanceDetailResponse detail = new WorkflowInstanceDetailResponse();
        detail.setCurrentStepName("职能部门审批人审批");
        detail.setCurrentTaskId("880");
        detail.setSourceOrgName("战略发展部");

        PlanReportSubmittedEvent event = new PlanReportSubmittedEvent(701L, "202610", 44L, 9L);

        when(reportApplicationService.findReportById(701L)).thenReturn(Optional.of(report));
        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PLAN_REPORT", 701L)).thenReturn(List.of());
        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PlanReport", 701L)).thenReturn(List.of());
        when(businessWorkflowService.startWorkflow(any(StartWorkflowRequest.class), eq(9L), eq(44L)))
                .thenReturn(started);
        when(workflowDefinitionQueryService.getAuditFlowDefByCode("PLAN_APPROVAL_FUNCDEPT")).thenReturn(flowDef);
        when(workflowReadModelService.getInstanceDetail("91")).thenReturn(detail);
        when(approverResolver.resolveApproverName(9L)).thenReturn("张三");
        when(auditInstanceRepository.findById(91L)).thenReturn(Optional.empty());
        when(approverResolver.resolveCandidates(eq(approvalStep), eq(44L), any())).thenReturn(List.of(
                ApproverCandidateResponse.builder().userId(55L).realName("李四").orgId(44L).build()));
        when(organizationRepository.findById(44L)).thenReturn(Optional.of(reportOrg));

        listener.handlePlanReportSubmitted(event);

        ArgumentCaptor<DingTalkTodoProvider.ApprovalTodoPush> captor =
                ArgumentCaptor.forClass(DingTalkTodoProvider.ApprovalTodoPush.class);
        verify(dingTalkTodoProvider).pushApprovalTodo(captor.capture());
        assertEquals("教务处", captor.getValue().departmentName());
    }

    @Test
    @DisplayName("填报组织名解析不到时保持原值（下发方向组织名），不推送空部门")
    void pushApprovalTodo_shouldFallbackToSourceOrgNameWhenReportOrgMissing() {
        PlanReport report = new PlanReport();
        report.setId(702L);
        report.setPlanId(3L);
        report.setReportMonth("202610");
        report.setReportOrgId(44L);
        report.setReportOrgType(ReportOrgType.FUNC_DEPT);
        report.setTitle("202610 教务处月报");

        WorkflowInstanceResponse started = new WorkflowInstanceResponse();
        started.setInstanceId("92");

        AuditStepDef approvalStep = new AuditStepDef();
        approvalStep.setId(8L);
        approvalStep.setStepName("职能部门审批人审批");
        approvalStep.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        approvalStep.setStepOrder(2);

        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setSteps(List.of(approvalStep));

        WorkflowInstanceDetailResponse detail = new WorkflowInstanceDetailResponse();
        detail.setCurrentStepName("职能部门审批人审批");
        detail.setCurrentTaskId("881");
        detail.setSourceOrgName("战略发展部");

        PlanReportSubmittedEvent event = new PlanReportSubmittedEvent(702L, "202610", 44L, 9L);

        when(reportApplicationService.findReportById(702L)).thenReturn(Optional.of(report));
        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PLAN_REPORT", 702L)).thenReturn(List.of());
        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PlanReport", 702L)).thenReturn(List.of());
        when(businessWorkflowService.startWorkflow(any(StartWorkflowRequest.class), eq(9L), eq(44L)))
                .thenReturn(started);
        when(workflowDefinitionQueryService.getAuditFlowDefByCode("PLAN_APPROVAL_FUNCDEPT")).thenReturn(flowDef);
        when(workflowReadModelService.getInstanceDetail("92")).thenReturn(detail);
        when(approverResolver.resolveApproverName(9L)).thenReturn("张三");
        when(auditInstanceRepository.findById(92L)).thenReturn(Optional.empty());
        when(approverResolver.resolveCandidates(eq(approvalStep), eq(44L), any())).thenReturn(List.of(
                ApproverCandidateResponse.builder().userId(55L).realName("李四").orgId(44L).build()));
        when(organizationRepository.findById(44L)).thenReturn(Optional.empty());

        listener.handlePlanReportSubmitted(event);

        ArgumentCaptor<DingTalkTodoProvider.ApprovalTodoPush> captor =
                ArgumentCaptor.forClass(DingTalkTodoProvider.ApprovalTodoPush.class);
        verify(dingTalkTodoProvider).pushApprovalTodo(captor.capture());
        assertEquals("战略发展部", captor.getValue().departmentName());
    }
}
