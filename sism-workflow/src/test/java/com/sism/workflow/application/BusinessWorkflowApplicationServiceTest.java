package com.sism.workflow.application;

import com.sism.shared.domain.integration.DingTalkTodoProvider;
import com.sism.shared.domain.notification.NotificationProvider;
import com.sism.shared.domain.user.UserProvider;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.definition.WorkflowPreviewQueryService;
import com.sism.workflow.application.query.WorkflowReadModelMapper;
import com.sism.workflow.application.query.WorkflowReadModelService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.query.repository.WorkflowQueryRepository;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.WorkflowTask;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.WorkflowTaskRepository;
import com.sism.workflow.interfaces.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessWorkflowApplicationServiceTest {

    @Mock
    private WorkflowDefinitionQueryService workflowDefinitionQueryService;

    @Mock
    private AuditInstanceRepository auditInstanceRepository;

    @Mock
    private WorkflowQueryRepository workflowQueryRepository;

    @Mock
    private WorkflowApplicationService workflowApplicationService;

    @Mock
    private WorkflowReadModelService workflowReadModelService;

    @Mock
    private WorkflowReadModelMapper workflowReadModelMapper;

    @Mock
    private WorkflowPreviewQueryService workflowPreviewQueryService;

    @Mock
    private WorkflowTaskRepository workflowTaskRepository;

    @Mock
    private ApproverResolver approverResolver;

    @Mock
    private UserProvider userProvider;

    @Mock
    private NotificationProvider notificationProvider;

    @Mock
    private DingTalkTodoProvider dingTalkTodoProvider;

    @InjectMocks
    private BusinessWorkflowApplicationService businessWorkflowApplicationService;

    @Test
    void startWorkflow_shouldReturnStartedInstance() {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(1L);
        flowDef.setFlowCode("REPORT_APPROVAL");
        flowDef.setFlowName("报告审批");
        flowDef.setEntityType("PLAN");
        flowDef.setIsActive(true);

        AuditInstance started = new AuditInstance();
        started.setId(9L);
        started.setFlowDefId(1L);
        started.setEntityId(18L);
        started.setStatus(AuditInstance.STATUS_PENDING);

        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setWorkflowCode("REPORT_APPROVAL");
        request.setBusinessEntityId(18L);
        request.setBusinessEntityType("PLAN_REPORT");

        when(workflowDefinitionQueryService.getAuditFlowDefByCode("REPORT_APPROVAL")).thenReturn(flowDef);
        when(auditInstanceRepository.hasActiveInstance(18L, "PLAN_REPORT")).thenReturn(false);
        when(auditInstanceRepository.hasActiveInstance(18L, "PlanReport")).thenReturn(false);
        when(workflowApplicationService.startAuditInstance(any(AuditInstance.class), any(), any())).thenReturn(started);
        when(workflowReadModelMapper.toInstanceResponse(started)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("9").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.startWorkflow(request, 1L, 2L);

        ArgumentCaptor<AuditInstance> captor = ArgumentCaptor.forClass(AuditInstance.class);
        verify(workflowApplicationService).startAuditInstance(captor.capture(), any(), any());

        assertEquals("9", response.getInstanceId());
        assertEquals("IN_REVIEW", response.getStatus());
        assertEquals(1L, captor.getValue().getFlowDefId());
        assertEquals("PLAN_REPORT", captor.getValue().getEntityType());
        assertEquals(18L, captor.getValue().getEntityId());
    }

    @Test
    void startWorkflowInstance_shouldForwardDefinitionAndEntityToUnifiedStartFlow() {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(3L);
        flowDef.setFlowCode("PLAN_DISPATCH");
        flowDef.setFlowName("计划下发审批");
        flowDef.setEntityType("PLAN");
        flowDef.setIsActive(true);

        AuditInstance started = new AuditInstance();
        started.setId(30L);
        started.setFlowDefId(3L);
        started.setEntityId(88L);
        started.setStatus(AuditInstance.STATUS_PENDING);

        StartInstanceRequest request = new StartInstanceRequest();
        request.setBusinessEntityId(88L);

        when(workflowDefinitionQueryService.getAuditFlowDefById(3L)).thenReturn(flowDef);
        when(workflowDefinitionQueryService.getAuditFlowDefByCode("PLAN_DISPATCH")).thenReturn(flowDef);
        when(auditInstanceRepository.hasActiveInstance(88L, "PLAN")).thenReturn(false);
        when(workflowApplicationService.startAuditInstance(any(AuditInstance.class), any(), any()))
                .thenReturn(started);
        when(workflowReadModelMapper.toInstanceResponse(started)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("30").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.startWorkflowInstance(
                "3", request, 10L, 20L);

        assertEquals("30", response.getInstanceId());

        ArgumentCaptor<AuditInstance> captor = ArgumentCaptor.forClass(AuditInstance.class);
        verify(workflowApplicationService).startAuditInstance(captor.capture(), any(), any());
        assertEquals(3L, captor.getValue().getFlowDefId());
        assertEquals(88L, captor.getValue().getEntityId());
        assertEquals("PLAN", captor.getValue().getEntityType());
    }

    @Test
    void cancelInstance_shouldRejectNonNumericInstanceId() {
        assertThrows(IllegalArgumentException.class,
                () -> businessWorkflowApplicationService.cancelInstance("not-a-number", 1L));
    }

    @Test
    void startWorkflow_shouldResumeWithdrawnInstanceBeforeCreatingNewOne() {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(7L);
        flowDef.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        flowDef.setEntityType("PLAN");
        flowDef.setIsActive(true);

        AuditInstance resumable = new AuditInstance();
        resumable.setId(501L);
        resumable.setFlowDefId(7L);
        resumable.setEntityType("PLAN");
        resumable.setEntityId(9001L);
        resumable.setStatus(AuditInstance.STATUS_PENDING);

        var withdrawnStep = new com.sism.workflow.domain.runtime.AuditStepInstance();
        withdrawnStep.setId(601L);
        withdrawnStep.setStepNo(2);
        withdrawnStep.setStepName("部门审批");
        withdrawnStep.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        resumable.addStepInstance(withdrawnStep);

        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setWorkflowCode("PLAN_APPROVAL_FUNCDEPT");
        request.setBusinessEntityId(9001L);
        request.setBusinessEntityType("PLAN");

        when(workflowDefinitionQueryService.getAuditFlowDefByCode("PLAN_APPROVAL_FUNCDEPT")).thenReturn(flowDef);
        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PLAN", 9001L)).thenReturn(List.of(resumable));
        when(workflowApplicationService.resumeWithdrawnAuditInstance(resumable)).thenReturn(resumable);
        when(workflowReadModelMapper.toInstanceResponse(resumable)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("501").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.startWorkflow(request, 188L, 35L);

        assertEquals("501", response.getInstanceId());
        verify(workflowApplicationService).resumeWithdrawnAuditInstance(resumable);
    }

    @Test
    void startWorkflow_shouldResumeRejectedLegacyInstanceWhenReturnedSubmitStepExists() {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(8L);
        flowDef.setFlowCode("PLAN_APPROVAL_COLLEGE");
        flowDef.setEntityType("PLAN_REPORT");
        flowDef.setIsActive(true);

        AuditInstance resumable = new AuditInstance();
        resumable.setId(601L);
        resumable.setFlowDefId(8L);
        resumable.setEntityType("PLAN_REPORT");
        resumable.setEntityId(77L);
        resumable.setStatus(AuditInstance.STATUS_REJECTED);

        var withdrawnStep = new com.sism.workflow.domain.runtime.AuditStepInstance();
        withdrawnStep.setId(701L);
        withdrawnStep.setStepNo(5);
        withdrawnStep.setStepName("填报人提交");
        withdrawnStep.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        resumable.addStepInstance(withdrawnStep);

        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setWorkflowCode("PLAN_APPROVAL_COLLEGE");
        request.setBusinessEntityId(77L);
        request.setBusinessEntityType("PLAN_REPORT");

        when(workflowDefinitionQueryService.getAuditFlowDefByCode("PLAN_APPROVAL_COLLEGE")).thenReturn(flowDef);
        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PLAN_REPORT", 77L)).thenReturn(List.of(resumable));
        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PlanReport", 77L)).thenReturn(List.of());
        when(workflowApplicationService.resumeWithdrawnAuditInstance(resumable)).thenReturn(resumable);
        when(workflowReadModelMapper.toInstanceResponse(resumable)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("601").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.startWorkflow(request, 301L, 37L);

        assertEquals("601", response.getInstanceId());
        verify(workflowApplicationService).resumeWithdrawnAuditInstance(resumable);
    }

    @Test
    void approveTask_shouldRejectUnauthorizedUser() {
        AuditInstance instance = new AuditInstance();
        instance.setId(1L);
        instance.setStepInstances(List.of());

        ApprovalRequest request = new ApprovalRequest();
        when(auditInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

        assertThrows(SecurityException.class, () -> businessWorkflowApplicationService.approveTask("1", request, 7L));
    }

    @Test
    void approveTask_shouldResolveOwningInstanceWhenTaskIdIsStepInstanceId() {
        AuditInstance instance = new AuditInstance();
        instance.setId(128L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setFlowDefId(3L);
        instance.setRequesterOrgId(35L);

        com.sism.workflow.domain.runtime.AuditStepInstance currentStep =
                new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentStep.setId(256L);
        currentStep.setStepDefId(9L);
        currentStep.setStepNo(2);
        currentStep.setStepName("分管校领导审批");
        currentStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(currentStep);

        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(9L);
        stepDef.setRoleId(4L);
        flowDef.setSteps(List.of(stepDef));

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(256L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(3L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(stepDef, 9L, 35L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 9L, "同意")).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("128").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("256", request, 9L);

        assertEquals("128", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 9L, "同意");
    }

    @Test
    void approveTask_shouldResolveOwningInstanceWhenTaskIdIsWorkflowTaskId() {
        AuditInstance instance = new AuditInstance();
        instance.setId(133L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setFlowDefId(1L);
        instance.setRequesterOrgId(35L);

        com.sism.workflow.domain.runtime.AuditStepInstance currentStep =
                new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentStep.setId(356L);
        currentStep.setStepDefId(2L);
        currentStep.setStepNo(2);
        currentStep.setStepName("战略发展部负责人审批");
        currentStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(currentStep);

        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(2L);
        stepDef.setRoleId(3L);
        flowDef.setSteps(List.of(stepDef));

        WorkflowTask workflowTask = new WorkflowTask();
        workflowTask.setId(392L);
        workflowTask.setWorkflowId("133");

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findById(392L)).thenReturn(Optional.empty());
        when(auditInstanceRepository.findByStepInstanceId(392L)).thenReturn(Optional.empty());
        when(workflowTaskRepository.findById(392L)).thenReturn(Optional.of(workflowTask));
        when(auditInstanceRepository.findById(133L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(stepDef, 9L, 35L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 9L, "同意")).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("133").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("392", request, 9L);

        assertEquals("133", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 9L, "同意");
    }

    @Test
    void approveTask_shouldAllowPlanApprovalFlowWithIndicatorReportPermission() {
        AuditInstance instance = new AuditInstance();
        instance.setId(4036L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setFlowDefId(3L);
        instance.setRequesterOrgId(36L);
        instance.setEntityType("PLAN");

        com.sism.workflow.domain.runtime.AuditStepInstance currentStep =
                new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentStep.setId(5L);
        currentStep.setStepDefId(8L);
        currentStep.setStepNo(2);
        currentStep.setStepName("职能部门审批人审批");
        currentStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(currentStep);

        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(3L);
        flowDef.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(8L);
        stepDef.setRoleId(2L);
        flowDef.setSteps(List.of(stepDef));

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(5L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(3L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(stepDef, 192L, 36L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 192L, "同意")).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("4036").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("5", request, 192L);

        assertEquals("4036", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 192L, "同意");
    }

    @Test
    void approveTask_shouldNotCreateApprovedNotificationBeforeWorkflowFinishes() {
        AuditInstance instance = new AuditInstance();
        instance.setId(7036L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setFlowDefId(1L);
        instance.setRequesterOrgId(35L);
        instance.setEntityType("PLAN");
        instance.setEntityId(7036L);

        com.sism.workflow.domain.runtime.AuditStepInstance currentStep =
                new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentStep.setId(3L);
        currentStep.setStepDefId(3L);
        currentStep.setStepNo(3);
        currentStep.setStepName("分管校领导审批");
        currentStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(currentStep);

        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(1L);
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(3L);
        stepDef.setRoleId(4L);
        stepDef.setStepName("分管校领导审批");
        flowDef.setSteps(List.of(stepDef));

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(3L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(stepDef, 401L, 35L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 401L, "同意")).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("7036").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("3", request, 401L);

        assertEquals("7036", response.getInstanceId());
        verify(notificationProvider, never()).createApprovalResultNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()
        );
    }

    @Test
    void approveTask_shouldAllowUserWithoutLegacyPermissionCode() {
        AuditInstance instance = new AuditInstance();
        instance.setId(140L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setFlowDefId(4L);
        instance.setRequesterOrgId(22L);
        instance.setEntityType("PLAN");

        com.sism.workflow.domain.runtime.AuditStepInstance currentStep =
                new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentStep.setId(400L);
        currentStep.setStepDefId(12L);
        currentStep.setStepNo(1);
        currentStep.setStepName("战略发展部审批");
        currentStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(currentStep);

        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(12L);
        stepDef.setRoleId(3L);
        flowDef.setSteps(List.of(stepDef));

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(400L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(stepDef, 9L, 22L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 9L, "同意")).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("140").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("400", request, 9L);

        assertEquals("140", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 9L, "同意");
    }

    @Test
    void getMyApprovedInstances_shouldDelegateToReadModelService() {
        PageResult<WorkflowInstanceResponse> expected = PageResult.of(
                List.of(WorkflowInstanceResponse.builder().instanceId("11").flowCode("PLAN_DISPATCH_STRATEGY").build()),
                1,
                1,
                20
        );
        when(workflowReadModelService.getMyApprovedInstances(124L, 1, 20)).thenReturn(expected);

        PageResult<WorkflowInstanceResponse> actual =
                businessWorkflowApplicationService.getMyApprovedInstances(124L, 1, 20);

        assertEquals(expected, actual);
    }

    @Test
    void getMyAppliedInstances_shouldDelegateToReadModelService() {
        PageResult<WorkflowInstanceResponse> expected = PageResult.of(
                List.of(WorkflowInstanceResponse.builder().instanceId("13").flowCode("PLAN_APPROVAL_FUNCDEPT").build()),
                1,
                1,
                20
        );
        when(workflowReadModelService.getMyAppliedInstances(223L, 1, 20)).thenReturn(expected);

        PageResult<WorkflowInstanceResponse> actual =
                businessWorkflowApplicationService.getMyAppliedInstances(223L, 1, 20);

        assertEquals(expected, actual);
    }

    @Test
    void decideTask_shouldDispatchApproveBranch() {
        WorkflowTaskDecisionRequest request = new WorkflowTaskDecisionRequest();
        request.setApproved(true);
        request.setComment("通过");

        AuditInstance instance = new AuditInstance();
        instance.setId(88L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setFlowDefId(4L);
        instance.setRequesterOrgId(56L);
        var currentStep = new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentStep.setId(501L);
        currentStep.setStepDefId(13L);
        currentStep.setStepNo(2);
        currentStep.setStepName("一级审批");
        currentStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(currentStep);

        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(13L);
        stepDef.setRoleId(4L);
        flowDef.setSteps(List.of(stepDef));

        when(auditInstanceRepository.findByStepInstanceId(501L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(stepDef, 11L, 56L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 11L, "通过")).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("88").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.decideTask("501", request, 11L);

        assertEquals("88", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 11L, "通过");
    }

    @Test
    void decideTask_shouldDispatchRejectBranch() {
        WorkflowTaskDecisionRequest request = new WorkflowTaskDecisionRequest();
        request.setApproved(false);
        request.setComment("打回");

        AuditInstance instance = new AuditInstance();
        instance.setId(89L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setFlowDefId(2L);
        instance.setRequesterOrgId(44L);
        var currentStep = new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentStep.setId(601L);
        currentStep.setStepDefId(6L);
        currentStep.setStepNo(2);
        currentStep.setStepName("一级审批");
        currentStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(currentStep);

        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(6L);
        stepDef.setRoleId(4L);
        flowDef.setSteps(List.of(stepDef));

        when(auditInstanceRepository.findByStepInstanceId(601L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(2L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(stepDef, 12L, 44L, instance)).thenReturn(true);
        when(workflowApplicationService.rejectAuditInstance(instance, 12L, "打回")).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("89").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.decideTask("601", request, 12L);

        assertEquals("89", response.getInstanceId());
        verify(workflowApplicationService).rejectAuditInstance(instance, 12L, "打回");
    }

    @Test
    void listDefinitions_shouldMapPageResults() {
        WorkflowDefinitionResponse item = WorkflowDefinitionResponse.builder()
                .definitionId("1")
                .definitionCode("REPORT_APPROVAL")
                .definitionName("报告审批")
                .build();
        PageResult<WorkflowDefinitionResponse> expected = PageResult.of(List.of(item), 1, 1, 10);
        when(workflowReadModelService.listDefinitions(1, 10)).thenReturn(expected);

        PageResult<WorkflowDefinitionResponse> result = businessWorkflowApplicationService.listDefinitions(1, 10);

        assertEquals(1, result.getTotal());
        assertEquals("报告审批", result.getItems().get(0).getDefinitionName());
    }

    @Test
    void createDefinition_shouldMapTemplateWithExplicitSteps() {
        CreateWorkflowDefinitionRequest request = new CreateWorkflowDefinitionRequest();
        request.setDefinitionCode("PLAN_DISPATCH");
        request.setDefinitionName("计划下发审批");
        request.setCategory("PLAN");
        request.setDescription("固定模板");
        request.setActive(true);

        CreateWorkflowStepDefinitionRequest submitStep = new CreateWorkflowStepDefinitionRequest();
        submitStep.setStepName("提交");
        submitStep.setStepOrder(1);
        submitStep.setStepType("SUBMIT");

        CreateWorkflowStepDefinitionRequest approvalStep = new CreateWorkflowStepDefinitionRequest();
        approvalStep.setStepName("一级审批");
        approvalStep.setStepOrder(2);
        approvalStep.setStepType("APPROVAL");
        approvalStep.setRoleId(3L);

        request.setSteps(List.of(submitStep, approvalStep));

        AuditFlowDef created = new AuditFlowDef();
        created.setId(10L);
        created.setFlowCode("PLAN_DISPATCH");
        created.setFlowName("计划下发审批");
        created.setEntityType("PLAN");
        created.setIsActive(true);

        when(workflowDefinitionQueryService.createAuditFlowDef(any(AuditFlowDef.class))).thenReturn(created);
        when(workflowReadModelMapper.toDefinitionResponse(created)).thenReturn(
                WorkflowDefinitionResponse.builder()
                        .definitionId("10")
                        .definitionCode("PLAN_DISPATCH")
                        .definitionName("计划下发审批")
                        .build()
        );

        WorkflowDefinitionResponse response = businessWorkflowApplicationService.createDefinition(request);

        assertEquals("10", response.getDefinitionId());
        assertEquals("PLAN_DISPATCH", response.getDefinitionCode());

        ArgumentCaptor<AuditFlowDef> captor = ArgumentCaptor.forClass(AuditFlowDef.class);
        verify(workflowDefinitionQueryService).createAuditFlowDef(captor.capture());
        assertEquals("PLAN_DISPATCH", captor.getValue().getFlowCode());
        assertEquals(2, captor.getValue().getSteps().size());
        assertEquals("SUBMIT", captor.getValue().getSteps().get(0).getStepType());
        assertEquals("APPROVAL", captor.getValue().getSteps().get(1).getStepType());
    }

    @Test
    void getInstanceDetailByBusiness_shouldIgnoreLatestWithdrawnInstance() {
        WorkflowInstanceDetailResponse expected = new WorkflowInstanceDetailResponse();
        expected.setInstanceId("300");
        when(workflowReadModelService.getInstanceDetailByBusiness("PLAN", 77L)).thenReturn(expected);

        WorkflowInstanceDetailResponse actual = businessWorkflowApplicationService.getInstanceDetailByBusiness("PLAN", 77L);

        assertEquals("300", actual.getInstanceId());
    }
}
