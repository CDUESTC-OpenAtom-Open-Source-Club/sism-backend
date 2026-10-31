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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    // ==================== 2026-10-30 取消异动（退回待重报整链终结） ====================

    /** 构造 PLAN_MUTATION_STRATEGY 异动链的「驳回退回填报人」实例（WITHDRAWN、未完结）。 */
    private AuditInstance returnedMutationInstance(
            Long id, Long flowDefId, String batchId, Long entityId, Long requesterId) {
        AuditInstance instance = new AuditInstance();
        instance.setId(id);
        instance.setFlowDefId(flowDefId);
        instance.setEntityType("INDICATOR");
        instance.setEntityId(entityId);
        instance.setBatchId(batchId);
        instance.setRequesterId(requesterId);
        instance.setStatus(AuditInstance.STATUS_WITHDRAWN);
        instance.setCompletedAt(null);

        var rejectedStep = new com.sism.workflow.domain.runtime.AuditStepInstance();
        rejectedStep.setStepNo(2);
        rejectedStep.setStatus(AuditInstance.STEP_STATUS_REJECTED);
        rejectedStep.setApproverId(300L);
        instance.addStepInstance(rejectedStep);

        var returnedSubmit = new com.sism.workflow.domain.runtime.AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setApproverId(requesterId);
        returnedSubmit.setComment("驳回后退回填报人重新提交");
        instance.addStepInstance(returnedSubmit);
        return instance;
    }

    private AuditFlowDef mutationFlowDef(Long id) {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(id);
        flowDef.setFlowCode("PLAN_MUTATION_STRATEGY");
        flowDef.setFlowName("指标异动审批（战略发展部）");
        flowDef.setEntityType("INDICATOR");
        return flowDef;
    }

    @Test
    void cancelReturnedMutationBatch_shouldRejectNonRequester() {
        AuditInstance instance = returnedMutationInstance(60L, 8L, "batch-77", 2001L, 100L);
        when(auditInstanceRepository.findById(60L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(mutationFlowDef(8L));

        assertThrows(SecurityException.class,
                () -> businessWorkflowApplicationService.cancelReturnedMutationBatch("60", 999L));

        verify(workflowApplicationService, never()).cancelReturnedMutationBatch(any());
        verify(auditInstanceRepository, never()).save(any(AuditInstance.class));
    }

    @Test
    void cancelReturnedMutationBatch_shouldRejectNonMutationChain() {
        AuditInstance instance = returnedMutationInstance(61L, 9L, "batch-78", 2002L, 100L);
        AuditFlowDef reportFlow = new AuditFlowDef();
        reportFlow.setId(9L);
        reportFlow.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        when(auditInstanceRepository.findById(61L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(9L)).thenReturn(reportFlow);

        assertThrows(IllegalArgumentException.class,
                () -> businessWorkflowApplicationService.cancelReturnedMutationBatch("61", 100L));

        verify(workflowApplicationService, never()).cancelReturnedMutationBatch(any());
    }

    @Test
    void cancelReturnedMutationBatch_shouldCancelWholeBatchByBatchId() {
        AuditInstance first = returnedMutationInstance(70L, 8L, "batch-88", 2001L, 100L);
        AuditInstance second = returnedMutationInstance(71L, 8L, "batch-88", 2002L, 100L);
        when(auditInstanceRepository.findById(70L)).thenReturn(Optional.of(first));
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(mutationFlowDef(8L));
        when(auditInstanceRepository.findByBatchId("batch-88")).thenReturn(List.of(first, second));
        when(workflowApplicationService.cancelReturnedMutationBatch(any()))
                .thenAnswer(invocation -> {
                    List<AuditInstance> batch = invocation.getArgument(0);
                    batch.forEach(item -> item.cancelReturned());
                    return batch;
                });

        List<AuditInstance> cancelled =
                businessWorkflowApplicationService.cancelReturnedMutationBatch("70", 100L);

        assertEquals(2, cancelled.size());
        assertEquals("70", cancelled.get(0).getId().toString());
        for (AuditInstance item : cancelled) {
            assertEquals(AuditInstance.STATUS_CANCELLED, item.getStatus());
            assertNotNull(item.getCompletedAt());
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditInstance>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(workflowApplicationService).cancelReturnedMutationBatch(batchCaptor.capture());
        assertEquals(2, batchCaptor.getValue().size());
        // 终结后清理钉钉待办（驳回时已清理过一次，幂等兜底）
        verify(dingTalkTodoProvider).completeInstanceTodos(70L);
        verify(dingTalkTodoProvider).completeInstanceTodos(71L);
    }

    @Test
    void cancelReturnedMutationBatch_shouldCancelSingleInstanceWithoutBatchId() {
        AuditInstance instance = returnedMutationInstance(72L, 8L, null, 2003L, 100L);
        when(auditInstanceRepository.findById(72L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(mutationFlowDef(8L));
        when(workflowApplicationService.cancelReturnedMutationBatch(any()))
                .thenAnswer(invocation -> {
                    List<AuditInstance> batch = invocation.getArgument(0);
                    batch.forEach(item -> item.cancelReturned());
                    return batch;
                });

        List<AuditInstance> cancelled =
                businessWorkflowApplicationService.cancelReturnedMutationBatch("72", 100L);

        assertEquals(1, cancelled.size());
        assertEquals(AuditInstance.STATUS_CANCELLED, cancelled.get(0).getStatus());
        // 无批次号的孤例不查 batch，直接终结自身
        verify(auditInstanceRepository, never()).findByBatchId(any());
    }

    // ==================== 2026-10-30 异动撤回（退回填报人活链） ====================

    /** 构造 PLAN_MUTATION_STRATEGY 异动链的「审批中」实例（提交步骤 APPROVED + 审批 PENDING）。 */
    private AuditInstance inReviewMutationInstance(
            Long id, Long flowDefId, String batchId, Long entityId, Long requesterId) {
        AuditInstance instance = new AuditInstance();
        instance.setId(id);
        instance.setFlowDefId(flowDefId);
        instance.setEntityType("INDICATOR");
        instance.setEntityId(entityId);
        instance.setBatchId(batchId);
        instance.setRequesterId(requesterId);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setCompletedAt(null);

        var submitStep = new com.sism.workflow.domain.runtime.AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitStep.setApproverId(requesterId);
        instance.addStepInstance(submitStep);

        var currentApproval = new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentApproval.setStepNo(2);
        currentApproval.setStatus(AuditInstance.STEP_STATUS_PENDING);
        currentApproval.setApproverId(300L);
        instance.addStepInstance(currentApproval);
        return instance;
    }

    @Test
    void withdrawMutationBatchToRequester_shouldRejectNonRequester() {
        AuditInstance instance = inReviewMutationInstance(80L, 8L, "batch-101", 3001L, 100L);
        when(auditInstanceRepository.findById(80L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(mutationFlowDef(8L));

        assertThrows(SecurityException.class,
                () -> businessWorkflowApplicationService.withdrawMutationBatchToRequester("80", 999L));

        verify(workflowApplicationService, never()).withdrawMutationBatchToRequester(any());
        verify(auditInstanceRepository, never()).save(any(AuditInstance.class));
    }

    @Test
    void withdrawMutationBatchToRequester_shouldRejectNonMutationChain() {
        AuditInstance instance = inReviewMutationInstance(81L, 9L, "batch-102", 3002L, 100L);
        AuditFlowDef reportFlow = new AuditFlowDef();
        reportFlow.setId(9L);
        reportFlow.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        when(auditInstanceRepository.findById(81L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(9L)).thenReturn(reportFlow);

        assertThrows(IllegalArgumentException.class,
                () -> businessWorkflowApplicationService.withdrawMutationBatchToRequester("81", 100L));

        verify(workflowApplicationService, never()).withdrawMutationBatchToRequester(any());
    }

    @Test
    void withdrawMutationBatchToRequester_shouldWithdrawWholeBatchByBatchId() {
        AuditInstance first = inReviewMutationInstance(90L, 8L, "batch-103", 3003L, 100L);
        AuditInstance second = inReviewMutationInstance(91L, 8L, "batch-103", 3004L, 100L);
        when(auditInstanceRepository.findById(90L)).thenReturn(Optional.of(first));
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(mutationFlowDef(8L));
        when(auditInstanceRepository.findByBatchId("batch-103")).thenReturn(List.of(first, second));
        when(workflowApplicationService.withdrawMutationBatchToRequester(any()))
                .thenAnswer(invocation -> {
                    List<AuditInstance> batch = invocation.getArgument(0);
                    batch.forEach(AuditInstance::withdrawToRequester);
                    return batch;
                });

        List<AuditInstance> withdrawn =
                businessWorkflowApplicationService.withdrawMutationBatchToRequester("90", 100L);

        assertEquals(2, withdrawn.size());
        for (AuditInstance item : withdrawn) {
            // 链保持存活：WITHDRAWN 且 completedAt 为空（不进审批历史）
            assertEquals(AuditInstance.STATUS_WITHDRAWN, item.getStatus());
            assertNull(item.getCompletedAt());
            // 2026-10-30 二次定案（撤回零新增行）：提交步原地翻转 WITHDRAWN、行数恒等于初始 2
            assertEquals(2, item.getStepInstances().size());
            assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, item.getStepInstances().get(0).getStatus());
            assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, item.getStepInstances().get(1).getStatus());
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditInstance>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(workflowApplicationService).withdrawMutationBatchToRequester(batchCaptor.capture());
        assertEquals(2, batchCaptor.getValue().size());
        // 退回填报人后清理原待审批人的钉钉待办
        verify(dingTalkTodoProvider).completeInstanceTodos(90L);
        verify(dingTalkTodoProvider).completeInstanceTodos(91L);
    }

    @Test
    void withdrawMutationBatchToRequester_shouldWithdrawSingleInstanceWithoutBatchId() {
        AuditInstance instance = inReviewMutationInstance(92L, 8L, null, 3005L, 100L);
        when(auditInstanceRepository.findById(92L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(mutationFlowDef(8L));
        when(workflowApplicationService.withdrawMutationBatchToRequester(any()))
                .thenAnswer(invocation -> {
                    List<AuditInstance> batch = invocation.getArgument(0);
                    batch.forEach(AuditInstance::withdrawToRequester);
                    return batch;
                });

        List<AuditInstance> withdrawn =
                businessWorkflowApplicationService.withdrawMutationBatchToRequester("92", 100L);

        assertEquals(1, withdrawn.size());
        assertEquals(AuditInstance.STATUS_WITHDRAWN, withdrawn.get(0).getStatus());
        assertNull(withdrawn.get(0).getCompletedAt());
        // 无批次号的孤例不查 batch，直接退回自身
        verify(auditInstanceRepository, never()).findByBatchId(any());
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
        when(workflowApplicationService.resumeWithdrawnAuditInstance(resumable, null)).thenReturn(resumable);
        when(workflowReadModelMapper.toInstanceResponse(resumable)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("501").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.startWorkflow(request, 188L, 35L);

        assertEquals("501", response.getInstanceId());
        verify(workflowApplicationService).resumeWithdrawnAuditInstance(resumable, null);
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
        when(workflowApplicationService.resumeWithdrawnAuditInstance(resumable, null)).thenReturn(resumable);
        when(workflowReadModelMapper.toInstanceResponse(resumable)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("601").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.startWorkflow(request, 301L, 37L);

        assertEquals("601", response.getInstanceId());
        verify(workflowApplicationService).resumeWithdrawnAuditInstance(resumable, null);
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
        when(approverResolver.canUserApprove(stepDef, 9L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 9L, "同意", null)).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("128").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("256", request, 9L);

        assertEquals("128", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 9L, "同意", null);
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
        when(approverResolver.canUserApprove(stepDef, 9L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 9L, "同意", null)).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("133").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("392", request, 9L);

        assertEquals("133", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 9L, "同意", null);
    }

    /**
     * 2026-10-22 回归：实例 ID 与步骤实例 ID 撞号时，绝不能驳回到别的实例上。
     *
     * 线上故障：驳回实例 25（异动链，负责人节点待审），而 audit_step_instance 里恰好
     * 也存在 id=25 的步骤（属于早已撤回的实例 10）。旧实现先按步骤 ID 查，
     * 于是驳回打到了实例 10，报「Cannot reject: workflow is not in review」，
     * 用户看到「填了原因、点了确认、却没驳回成功」。
     */
    @Test
    void rejectTask_shouldPreferInstanceIdOverCollidingStepInstanceId() {
        // 目标实例：25，仍在审批中，负责人节点待审
        AuditInstance target = new AuditInstance();
        target.setId(25L);
        target.setStatus(AuditInstance.STATUS_PENDING);
        target.setFlowDefId(5L);
        target.setRequesterOrgId(35L);

        com.sism.workflow.domain.runtime.AuditStepInstance pendingStep =
                new com.sism.workflow.domain.runtime.AuditStepInstance();
        pendingStep.setId(71L);
        pendingStep.setStepDefId(17L);
        pendingStep.setStepNo(2);
        pendingStep.setStepName("战略发展部负责人审批");
        pendingStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        target.addStepInstance(pendingStep);

        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(17L);
        stepDef.setRoleId(3L);
        flowDef.setSteps(List.of(stepDef));

        // 撞号的旧实例：10，早已撤回
        AuditInstance colliding = new AuditInstance();
        colliding.setId(10L);
        colliding.setStatus(AuditInstance.STATUS_WITHDRAWN);

        RejectionRequest request = new RejectionRequest();
        request.setReason("不同意新增");

        when(auditInstanceRepository.findById(25L)).thenReturn(Optional.of(target));
        // 若实现仍先按步骤 ID 查，会命中这条错误映射。
        // 用 lenient：修复生效时这个桩本就不该被调用，这本身就是要断言的结果。
        lenient().when(auditInstanceRepository.findByStepInstanceId(25L)).thenReturn(Optional.of(colliding));
        when(workflowDefinitionQueryService.getAuditFlowDefById(5L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(stepDef, 189L, target)).thenReturn(true);
        when(workflowApplicationService.rejectAuditInstance(target, 189L, "不同意新增")).thenReturn(target);
        when(workflowReadModelMapper.toInstanceResponse(target)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("25").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response =
                businessWorkflowApplicationService.rejectTask("25", request, 189L);

        assertEquals("25", response.getInstanceId());
        // 必须驳回目标实例，而不是撞号的旧实例
        verify(workflowApplicationService).rejectAuditInstance(target, 189L, "不同意新增");
        verify(workflowApplicationService, never()).rejectAuditInstance(eq(colliding), any(), any());
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
        when(approverResolver.canUserApprove(stepDef, 192L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 192L, "同意", null)).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("4036").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("5", request, 192L);

        assertEquals("4036", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 192L, "同意", null);
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
        when(approverResolver.canUserApprove(stepDef, 401L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 401L, "同意", null)).thenReturn(instance);
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
        when(approverResolver.canUserApprove(stepDef, 9L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 9L, "同意", null)).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("140").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.approveTask("400", request, 9L);

        assertEquals("140", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 9L, "同意", null);
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
        when(approverResolver.canUserApprove(stepDef, 11L, instance)).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 11L, "通过", null)).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("88").status("IN_REVIEW").build()
        );

        WorkflowInstanceResponse response = businessWorkflowApplicationService.decideTask("501", request, 11L);

        assertEquals("88", response.getInstanceId());
        verify(workflowApplicationService).approveAuditInstance(instance, 11L, "通过", null);
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
        when(approverResolver.canUserApprove(stepDef, 12L, instance)).thenReturn(true);
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

    @Test
    void approveTask_shouldProjectAppraisalLevelWhenInstanceReachesTerminalApproved() {
        // B9：整条链走完（终态 APPROVED）时，本次 decision 的审核进度才投影到业务明细行
        AuditInstance instance = approvableInstance(8100L, 801L, AuditInstance.STATUS_APPROVED);
        AuditFlowDef flowDef = stepDefOf(instance);

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(801L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(any(AuditStepDef.class), eq(51L), eq(instance))).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 51L, "同意", "NORMAL")).thenReturn(instance);
        when(userProvider.getUserOrgId(51L)).thenReturn(Optional.empty());
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("8100").status("APPROVED").build()
        );

        com.sism.shared.domain.workflow.WorkflowBusinessContextPort port =
                org.mockito.Mockito.mock(com.sism.shared.domain.workflow.WorkflowBusinessContextPort.class);
        BusinessWorkflowApplicationService service = serviceWithPorts(List.of(port));

        service.approveTask("801", request, 51L, "NORMAL");

        verify(port).applyAppraisalLevel("PLAN", 8100L, "NORMAL");
    }

    @Test
    void approveTask_shouldNotProjectAppraisalLevelWhenInstanceStillPending() {
        // B9：中间节点（实例仍 PENDING）不投影，鉴定只留在 audit_step_instance.appraisal_level 留痕
        AuditInstance instance = approvableInstance(8200L, 821L, AuditInstance.STATUS_PENDING);
        AuditFlowDef flowDef = stepDefOf(instance);

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(821L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(any(AuditStepDef.class), eq(52L), eq(instance))).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 52L, "同意", "AHEAD")).thenReturn(instance);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("8200").status("IN_REVIEW").build()
        );

        com.sism.shared.domain.workflow.WorkflowBusinessContextPort port =
                org.mockito.Mockito.mock(com.sism.shared.domain.workflow.WorkflowBusinessContextPort.class);
        BusinessWorkflowApplicationService service = serviceWithPorts(List.of(port));

        service.approveTask("821", request, 52L, "AHEAD");

        verify(port, never()).applyAppraisalLevel(any(), any(), any());
    }

    @Test
    void approveTask_shouldBlockWithIndicatorNamesAndGuidanceWhenAppraisalMissing() {
        // 2026-10-30 用户定案：审批拦截文案要报指标名称（不报 ID）、不讲「资源冲突」、
        // 讲清楚去哪里做什么（到填报部门的指标填报页完成鉴定）。
        AuditInstance instance = approvableInstance(8300L, 831L, AuditInstance.STATUS_PENDING);
        AuditFlowDef flowDef = stepDefOf(instance);
        flowDef.getSteps().get(0).setStepOrder(4); // FUNCDEPT 链的可评级节点

        WorkflowInstanceDetailResponse detail = new WorkflowInstanceDetailResponse();
        detail.setInstanceId("8300");
        detail.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        detail.setTargetOrgName("计算机学院");

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(831L)).thenReturn(Optional.of(instance));
        when(workflowReadModelService.getInstanceDetail("8300")).thenReturn(detail);
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(any(AuditStepDef.class), eq(51L), eq(instance))).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 51L, "同意", "NORMAL")).thenReturn(instance);

        com.sism.shared.domain.workflow.WorkflowBusinessContextPort port =
                org.mockito.Mockito.mock(com.sism.shared.domain.workflow.WorkflowBusinessContextPort.class);
        when(port.findIndicatorAppraisalLevels(8300L)).thenReturn(java.util.Map.of(2007L, "NORMAL"));
        when(port.findIndicatorIdsByReport(8300L)).thenReturn(java.util.List.of(2007L, 2008L));
        when(port.findIndicatorNamesByReport(8300L)).thenReturn(java.util.Map.of(
                2007L, "教学运行质量", 2008L, "科研产出增长率"));
        when(port.findReportOrgName(8300L)).thenReturn(Optional.of("计算机学院"));

        BusinessWorkflowApplicationService service = serviceWithPorts(List.of(port));

        com.sism.exception.BusinessConflictException exception = assertThrows(
                com.sism.exception.BusinessConflictException.class,
                () -> service.approveTask("831", request, 51L, "NORMAL"));

        String message = exception.getMessage();
        assertTrue(message.contains("未完成审核：还有 1 项指标的审核进度未鉴定（『科研产出增长率』）"));
        assertTrue(message.contains("请到 计算机学院 的指标填报页完成全部指标的审核进度鉴定，再回到审批中心通过"));
        // 不报指标 ID（老板看不懂数字行列，界面不显示 ID）
        assertFalse(message.contains("2007"));
        assertFalse(message.contains("2008"));
        // 不出现「资源冲突」字样，也不再保留旧文案口径
        assertFalse(message.contains("资源冲突"));
        assertFalse(message.contains("存在未评定的审核进度"));
    }

    @Test
    void approveTask_shouldSummarizeManyUnappraisedIndicatorsWithTruncatedNames() {
        // 超过 3 项：列前 3 个 + 「等 N 项」；名称超 20 字截断补省略号；
        // 填报组织名取不到时兜底用审批卡目标部门名。
        AuditInstance instance = approvableInstance(8400L, 841L, AuditInstance.STATUS_PENDING);
        AuditFlowDef flowDef = stepDefOf(instance);
        flowDef.getSteps().get(0).setStepOrder(4);

        WorkflowInstanceDetailResponse detail = new WorkflowInstanceDetailResponse();
        detail.setInstanceId("8400");
        detail.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        detail.setTargetOrgName("计算机学院");

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(841L)).thenReturn(Optional.of(instance));
        when(workflowReadModelService.getInstanceDetail("8400")).thenReturn(detail);
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(any(AuditStepDef.class), eq(52L), eq(instance))).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 52L, "同意", null)).thenReturn(instance);

        com.sism.shared.domain.workflow.WorkflowBusinessContextPort port =
                org.mockito.Mockito.mock(com.sism.shared.domain.workflow.WorkflowBusinessContextPort.class);
        when(port.findIndicatorAppraisalLevels(8400L)).thenReturn(java.util.Map.of());
        when(port.findIndicatorIdsByReport(8400L)).thenReturn(java.util.List.of(1L, 2L, 3L, 4L));
        when(port.findIndicatorNamesByReport(8400L)).thenReturn(java.util.Map.of(
                1L, "指导思想贯彻情况落实与督查考核评价机制建设",
                2L, "教学质量",
                3L, "科研产出",
                4L, "社会服务"));
        when(port.findReportOrgName(8400L)).thenReturn(Optional.empty());

        BusinessWorkflowApplicationService service = serviceWithPorts(List.of(port));

        com.sism.exception.BusinessConflictException exception = assertThrows(
                com.sism.exception.BusinessConflictException.class,
                () -> service.approveTask("841", request, 52L, null));

        String message = exception.getMessage();
        assertTrue(message.contains("还有 4 项指标的审核进度未鉴定"));
        assertTrue(message.contains("『教学质量』"));
        assertTrue(message.contains(" 等 4 项"));
        assertTrue(message.contains("…")); // 超长指标名已截断
        assertFalse(message.contains("督查考核评价机制建设」")); // 完整长名不出现在文案
        assertFalse(message.contains("资源冲突"));
        assertTrue(message.contains("请到 计算机学院 的指标填报页完成全部指标的审核进度鉴定"));
    }

    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
     * 前端对整批异动逐实例调用 approveTask；同批次仍有同环节待审实例时
     * 整批动作未完成，期间不得为单个实例写结果/推进通知。
     */
    @Test
    void approveTask_shouldSuppressPerInstanceNotificationWhileMutationBatchIncomplete() {
        AuditInstance instance = mutationBatchInstance(810L, 811L, "batch-88", AuditInstance.STATUS_PENDING);
        AuditFlowDef flowDef = stepDefOf(instance);

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(811L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(any(AuditStepDef.class), eq(61L), eq(instance))).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 61L, "同意", null)).thenReturn(instance);
        // 同批次仍有同环节（stepNo=3）待审实例 → 整批动作未完成
        when(auditInstanceRepository.countPendingBatchSiblingsAtStep("batch-88", 810L, 3)).thenReturn(1L);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("810").status("IN_REVIEW").build()
        );

        serviceWithPorts(List.of()).approveTask("811", request, 61L);

        verify(notificationProvider, never()).createApprovalResultNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()
        );
        verify(notificationProvider, never()).createStepAdvancedNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
        verify(notificationProvider, never()).createFlowAdvancedNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    /**
     * 2026-10-30 用户定案：整批异动动作完成（批次内已无同环节待审实例）时，
     * 只产生 1 条结果通知。
     */
    @Test
    void approveTask_shouldEmitSingleResultNotificationWhenMutationBatchRoundCompletes() {
        AuditInstance instance = mutationBatchInstance(820L, 821L, "batch-88", AuditInstance.STATUS_APPROVED);
        instance.setRequesterId(77L);
        AuditFlowDef flowDef = stepDefOf(instance);

        ApprovalRequest request = new ApprovalRequest();
        request.setComment("同意");

        when(auditInstanceRepository.findByStepInstanceId(821L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(any(AuditStepDef.class), eq(62L), eq(instance))).thenReturn(true);
        when(workflowApplicationService.approveAuditInstance(instance, 62L, "同意", null)).thenReturn(instance);
        // 批次内已无同环节待审实例 → 本次是整批动作的最后一个，产生唯一 1 条结果通知
        when(auditInstanceRepository.countPendingBatchSiblingsAtStep("batch-88", 820L, 3)).thenReturn(0L);
        when(userProvider.getUserOrgId(62L)).thenReturn(Optional.empty());
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("820").status("APPROVED").build()
        );

        serviceWithPorts(List.of()).approveTask("821", request, 62L);

        verify(notificationProvider).createApprovalResultNotification(
                eq(77L), eq(62L), any(), eq(820L), eq("INDICATOR"), eq(820L),
                any(), any(), eq(true), eq("同意")
        );
    }

    /** 2026-10-30 用户定案：整批驳回同理——批次动作未完成时不逐实例写结果通知。 */
    @Test
    void rejectTask_shouldSuppressPerInstanceNotificationWhileMutationBatchIncomplete() {
        AuditInstance instance = mutationBatchInstance(830L, 831L, "batch-88", AuditInstance.STATUS_REJECTED);
        AuditFlowDef flowDef = stepDefOf(instance);

        RejectionRequest request = new RejectionRequest();
        request.setReason("不同意");

        when(auditInstanceRepository.findByStepInstanceId(831L)).thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);
        when(approverResolver.canUserApprove(any(AuditStepDef.class), eq(63L), eq(instance))).thenReturn(true);
        when(workflowApplicationService.rejectAuditInstance(instance, 63L, "不同意")).thenReturn(instance);
        when(auditInstanceRepository.countPendingBatchSiblingsAtStep("batch-88", 830L, 3)).thenReturn(1L);
        when(workflowReadModelMapper.toInstanceResponse(instance)).thenReturn(
                WorkflowInstanceResponse.builder().instanceId("830").status("REJECTED").build()
        );

        serviceWithPorts(List.of()).rejectTask("831", request, 63L);

        verify(notificationProvider, never()).createApprovalResultNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()
        );
    }

    private BusinessWorkflowApplicationService serviceWithPorts(
            List<com.sism.shared.domain.workflow.WorkflowBusinessContextPort> ports) {
        return new BusinessWorkflowApplicationService(
                workflowDefinitionQueryService,
                auditInstanceRepository,
                workflowQueryRepository,
                workflowApplicationService,
                workflowReadModelService,
                workflowReadModelMapper,
                workflowPreviewQueryService,
                workflowTaskRepository,
                approverResolver,
                userProvider,
                notificationProvider,
                dingTalkTodoProvider,
                ports
        );
    }

    private AuditInstance approvableInstance(long instanceId, long stepId, String statusAfterApproval) {
        AuditInstance instance = new AuditInstance();
        instance.setId(instanceId);
        instance.setStatus(statusAfterApproval);
        instance.setFlowDefId(1L);
        instance.setRequesterOrgId(35L);
        instance.setEntityType("PLAN");
        instance.setEntityId(instanceId);

        com.sism.workflow.domain.runtime.AuditStepInstance currentStep =
                new com.sism.workflow.domain.runtime.AuditStepInstance();
        currentStep.setId(stepId);
        currentStep.setStepDefId(3L);
        currentStep.setStepNo(3);
        currentStep.setStepName("分管校领导审批");
        currentStep.setStatus(AuditInstance.STEP_STATUS_PENDING);
        instance.addStepInstance(currentStep);
        return instance;
    }

    /** 2026-10-30 用户定案：异动按计划聚合——带 batch_id 的指标异动实例构造器。 */
    private AuditInstance mutationBatchInstance(long instanceId, long stepId, String batchId, String statusAfterApproval) {
        AuditInstance instance = approvableInstance(instanceId, stepId, statusAfterApproval);
        instance.setEntityType("INDICATOR");
        instance.setBatchId(batchId);
        return instance;
    }

    private AuditFlowDef stepDefOf(AuditInstance instance) {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(1L);
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(3L);
        stepDef.setRoleId(4L);
        stepDef.setStepName("分管校领导审批");
        flowDef.setSteps(List.of(stepDef));
        return flowDef;
    }
}
