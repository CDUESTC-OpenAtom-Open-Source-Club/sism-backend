package com.sism.workflow.application.runtime;

import com.sism.workflow.application.WorkflowBusinessStatusSyncService;
import com.sism.workflow.application.support.WorkflowEventDispatcher;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：
 * 「取消异动」终态动作——退回待重报的整批异动实例一次终结为 CANCELLED。
 */
@ExtendWith(MockitoExtension.class)
class CancelWorkflowUseCaseTest {

    @Mock
    private AuditInstanceRepository auditInstanceRepository;

    @Mock
    private WorkflowEventDispatcher workflowEventDispatcher;

    @Mock
    private WorkflowBusinessStatusSyncService workflowBusinessStatusSyncService;

    @Mock
    private com.sism.workflow.application.definition.WorkflowDefinitionQueryService workflowDefinitionQueryService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CancelWorkflowUseCase cancelWorkflowUseCase;

    /** 构造一条「驳回退回填报人」状态的异动实例（WITHDRAWN、completedAt 为空、有退回提交步骤）。 */
    private AuditInstance returnedMutationInstance(Long id, String batchId, Long entityId) {
        AuditInstance instance = new AuditInstance();
        instance.setId(id);
        instance.setEntityType("INDICATOR");
        instance.setEntityId(entityId);
        instance.setBatchId(batchId);
        instance.setRequesterId(100L);
        instance.setStatus(AuditInstance.STATUS_WITHDRAWN);
        instance.setCompletedAt(null);

        AuditStepInstance rejectedApproval = new AuditStepInstance();
        rejectedApproval.setStepNo(2);
        rejectedApproval.setStatus(AuditInstance.STEP_STATUS_REJECTED);
        rejectedApproval.setApproverId(300L);
        instance.addStepInstance(rejectedApproval);

        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setApproverId(100L);
        returnedSubmit.setComment("驳回后退回填报人重新提交");
        instance.addStepInstance(returnedSubmit);
        return instance;
    }

    @Test
    void cancelReturnedBatch_shouldTerminateWholeBatchIntoCancelledWithCompletedAt() {
        AuditInstance first = returnedMutationInstance(1L, "batch-88", 2001L);
        AuditInstance second = returnedMutationInstance(2L, "batch-88", 2002L);
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<AuditInstance> cancelled = cancelWorkflowUseCase.cancelReturnedBatch(List.of(first, second));

        assertEquals(2, cancelled.size());
        for (AuditInstance instance : cancelled) {
            assertEquals(AuditInstance.STATUS_CANCELLED, instance.getStatus());
            assertNotNull(instance.getCompletedAt());
        }
        // 整批逐实例落库、同步业务解锁（mutation_status 清理）并广播事件
        verify(auditInstanceRepository, times(2)).save(any(AuditInstance.class));
        verify(workflowBusinessStatusSyncService, times(2)).syncAfterWorkflowChanged(any(AuditInstance.class));
        verify(workflowEventDispatcher, times(2)).publish(any(AuditInstance.class));
    }

    @Test
    void cancelReturnedBatch_shouldRollBackWholeBatchWhenAnyInstanceNotReturned() {
        AuditInstance first = returnedMutationInstance(1L, "batch-88", 2001L);
        // 同批的第二条已被重新提交（IN_REVIEW），不满足取消条件 → 整批失败
        AuditInstance resubmitted = returnedMutationInstance(2L, "batch-88", 2002L);
        resubmitted.reactivateWithdrawnStep();
        assertEquals(AuditInstance.STATUS_PENDING, resubmitted.getStatus());

        assertThrows(IllegalStateException.class,
                () -> cancelWorkflowUseCase.cancelReturnedBatch(List.of(first, resubmitted)));

        // @Transactional 下异常即整批回滚；单测层面校验「失败后不再同步/广播」
        verify(workflowBusinessStatusSyncService, never()).syncAfterWorkflowChanged(any(AuditInstance.class));
        verify(workflowEventDispatcher, never()).publish(any(AuditInstance.class));
    }

    @Test
    void cancelReturnedBatch_shouldNotPublishWithdrawnChainNotification() {
        AuditInstance instance = returnedMutationInstance(1L, "batch-88", 2001L);
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        cancelWorkflowUseCase.cancelReturnedBatch(List.of(instance));

        // 退回待重报态没有待审批人，不发「审批链已撤回」实时通知（与审批中撤回 cancel 不同）
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==================== 2026-10-30 异动撤回（退回填报人活链） ====================

    /** 构造一条审批中的异动实例（提交步骤 APPROVED + 负责人审批 PENDING）。 */
    private AuditInstance inReviewMutationInstance(Long id, String batchId, Long entityId) {
        AuditInstance instance = new AuditInstance();
        instance.setId(id);
        instance.setFlowDefId(8L);
        instance.setEntityType("INDICATOR");
        instance.setEntityId(entityId);
        instance.setBatchId(batchId);
        instance.setRequesterId(100L);
        instance.setRequesterOrgId(35L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setCompletedAt(null);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStepDefId(11L);
        submitStep.setStepName("填报人提交");
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitStep.setApproverId(100L);
        instance.addStepInstance(submitStep);

        AuditStepInstance currentApproval = new AuditStepInstance();
        currentApproval.setStepNo(2);
        currentApproval.setStepDefId(12L);
        currentApproval.setStepName("战略发展部负责人审批");
        currentApproval.setStatus(AuditInstance.STEP_STATUS_PENDING);
        currentApproval.setApproverId(300L);
        instance.addStepInstance(currentApproval);
        return instance;
    }

    /** PLAN_MUTATION_STRATEGY 流程定义（提交步骤 + 审批步骤）。 */
    private com.sism.workflow.domain.definition.AuditFlowDef mutationFlowDef() {
        var flowDef = new com.sism.workflow.domain.definition.AuditFlowDef();
        flowDef.setId(8L);
        flowDef.setFlowCode("PLAN_MUTATION_STRATEGY");
        flowDef.setEntityType("INDICATOR");

        var submitStepDef = new com.sism.workflow.domain.definition.AuditStepDef();
        submitStepDef.setId(11L);
        submitStepDef.setStepOrder(1);
        submitStepDef.setStepName("填报人提交");
        submitStepDef.setStepType(com.sism.workflow.domain.definition.AuditStepDef.STEP_TYPE_SUBMIT);

        var approvalStepDef = new com.sism.workflow.domain.definition.AuditStepDef();
        approvalStepDef.setId(12L);
        approvalStepDef.setStepOrder(2);
        approvalStepDef.setStepName("战略发展部负责人审批");
        approvalStepDef.setStepType(com.sism.workflow.domain.definition.AuditStepDef.STEP_TYPE_APPROVAL);

        flowDef.addStep(submitStepDef);
        flowDef.addStep(approvalStepDef);
        return flowDef;
    }

    @Test
    void withdrawToRequesterBatch_shouldReturnWholeBatchToSubmitterWithAliveChain() {
        AuditInstance first = inReviewMutationInstance(1L, "batch-99", 2001L);
        AuditInstance second = inReviewMutationInstance(2L, "batch-99", 2002L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(mutationFlowDef());
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<AuditInstance> withdrawn =
                cancelWorkflowUseCase.withdrawToRequesterBatch(List.of(first, second));

        assertEquals(2, withdrawn.size());
        for (AuditInstance instance : withdrawn) {
            // 链保持存活：WITHDRAWN 且未完结（不进审批历史）
            assertEquals(AuditInstance.STATUS_WITHDRAWN, instance.getStatus());
            assertNull(instance.getCompletedAt());
            // 当前在审步骤被撤回留痕
            assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN,
                    instance.resolveLatestStepInstance().orElseThrow().getStatus());
            // 追加的退回提交步骤：WITHDRAWN、审批人=发起人、备注「提交人撤回」
            AuditStepInstance returnedStep = instance.getStepInstances().get(2);
            assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, returnedStep.getStatus());
            assertEquals(100L, returnedStep.getApproverId());
            assertEquals(35L, returnedStep.getApproverOrgId());
            assertEquals(11L, returnedStep.getStepDefId());
            assertEquals("填报人提交", returnedStep.getStepName());
            assertEquals("提交人撤回", returnedStep.getComment());
            assertNull(returnedStep.getApprovedAt());
        }
        // 整批逐实例落库、同步业务解锁（mutation_status 清理）并广播事件
        verify(auditInstanceRepository, times(2)).save(any(AuditInstance.class));
        verify(workflowBusinessStatusSyncService, times(2)).syncAfterWorkflowChanged(any(AuditInstance.class));
        verify(workflowEventDispatcher, times(2)).publish(any(AuditInstance.class));
    }

    @Test
    void withdrawToRequesterBatch_shouldRollBackWholeBatchWhenAnyInstanceNotInReview() {
        AuditInstance first = inReviewMutationInstance(1L, "batch-99", 2001L);
        // 同批的第二条已退回待重报（非 IN_REVIEW），不满足撤回条件 → 整批失败
        AuditInstance returned = returnedMutationInstance(2L, "batch-99", 2002L);
        assertEquals(AuditInstance.STATUS_WITHDRAWN, returned.getStatus());

        assertThrows(IllegalStateException.class,
                () -> cancelWorkflowUseCase.withdrawToRequesterBatch(List.of(first, returned)));

        // @Transactional 下异常即整批回滚；单测层面校验「失败后不再同步/广播」
        // （首个实例在失败前已 save，与 cancelReturnedBatch 回滚测试同口径）
        verify(workflowBusinessStatusSyncService, never()).syncAfterWorkflowChanged(any(AuditInstance.class));
        verify(workflowEventDispatcher, never()).publish(any(AuditInstance.class));
    }

    @Test
    void withdrawToRequesterBatch_shouldFallbackStepNameWhenFlowDefMissing() {
        AuditInstance instance = inReviewMutationInstance(1L, "batch-99", 2001L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(null);
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        cancelWorkflowUseCase.withdrawToRequesterBatch(List.of(instance));

        // 流程定义缺失兜底：退回提交步骤名含「提交」，退回链查询仍可按名识别
        AuditStepInstance returnedStep = instance.getStepInstances().get(2);
        assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, returnedStep.getStatus());
        assertTrue(returnedStep.getStepName().contains("提交"));
    }

    @Test
    void withdrawToRequesterBatch_shouldNotPublishWithdrawnChainNotification() {
        AuditInstance instance = inReviewMutationInstance(1L, "batch-99", 2001L);
        when(workflowDefinitionQueryService.getAuditFlowDefById(8L)).thenReturn(mutationFlowDef());
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        cancelWorkflowUseCase.withdrawToRequesterBatch(List.of(instance));

        // 退回填报人态没有待审批人，不发「审批链已撤回」实时通知（与 cancelReturnedBatch 同口径）
        verify(eventPublisher, never()).publishEvent(any());
    }
}
