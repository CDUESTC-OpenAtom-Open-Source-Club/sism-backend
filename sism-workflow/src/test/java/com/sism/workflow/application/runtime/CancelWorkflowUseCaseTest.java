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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：
 * 「取消异动」终态动作——退回待重报的整批异动实例一次终结为 CANCELLED。
 * 2026-10-30 二次定案（异动撤回零新增行）：撤回不再追加「退回提交步骤」行，
 * 提交步由领域层原地翻转 WITHDRAWN，链位回到填报人。
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

    @Test
    void cancelReturnedBatch_shouldTerminateNewFormWithdrawnChainToo() {
        // 2026-10-30 二次定案：新形态撤回链（提交步原地 WITHDRAWN，无追加行）同样可整批取消
        AuditInstance instance = inReviewMutationInstance(1L, "batch-88", 2001L);
        instance.withdrawToRequester();
        assertEquals(AuditInstance.STATUS_WITHDRAWN, instance.getStatus());
        assertEquals(2, instance.getStepInstances().size());
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<AuditInstance> cancelled = cancelWorkflowUseCase.cancelReturnedBatch(List.of(instance));

        assertEquals(1, cancelled.size());
        assertEquals(AuditInstance.STATUS_CANCELLED, cancelled.get(0).getStatus());
        assertNotNull(cancelled.get(0).getCompletedAt());
    }

    // ==================== 2026-10-30 异动撤回（退回填报人活链） ====================

    /** 构造一条审批中的异动实例（提交步骤 APPROVED + 负责人审批 PENDING）。 */
    private AuditInstance inReviewMutationInstance(Long id, String batchId, Long entityId) {
        AuditInstance instance = new AuditInstance();
        instance.setId(id);
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

    @Test
    void withdrawToRequesterBatch_shouldReturnWholeBatchToSubmitterWithAliveChain() {
        AuditInstance first = inReviewMutationInstance(1L, "batch-99", 2001L);
        AuditInstance second = inReviewMutationInstance(2L, "batch-99", 2002L);
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<AuditInstance> withdrawn =
                cancelWorkflowUseCase.withdrawToRequesterBatch(List.of(first, second));

        assertEquals(2, withdrawn.size());
        for (AuditInstance instance : withdrawn) {
            // 链保持存活：WITHDRAWN 且未完结（不进审批历史）
            assertEquals(AuditInstance.STATUS_WITHDRAWN, instance.getStatus());
            assertNull(instance.getCompletedAt());
            // 2026-10-30 二次定案（撤回零新增行）：不再追加退回提交行，行数恒等于初始 2
            assertEquals(2, instance.getStepInstances().size());
            // 在审审批步原地翻转 WITHDRAWN「提交人撤回」
            AuditStepInstance currentApproval = instance.getStepInstances().get(1);
            assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, currentApproval.getStatus());
            assertEquals("提交人撤回", currentApproval.getComment());
            assertNotNull(currentApproval.getApprovedAt());
            // 提交类步骤原地翻转 WITHDRAWN「提交人撤回」——链位回到填报人
            AuditStepInstance submitStep = instance.getStepInstances().get(0);
            assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, submitStep.getStatus());
            assertEquals("提交人撤回", submitStep.getComment());
            assertNotNull(submitStep.getApprovedAt());
            assertEquals(11L, submitStep.getStepDefId());
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
    void withdrawToRequesterBatch_shouldNotPublishWithdrawnChainNotification() {
        AuditInstance instance = inReviewMutationInstance(1L, "batch-99", 2001L);
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        cancelWorkflowUseCase.withdrawToRequesterBatch(List.of(instance));

        // 退回填报人态没有待审批人，不发「审批链已撤回」实时通知（与 cancelReturnedBatch 同口径）
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void withdrawToRequesterBatch_shouldKeepStepCountConstantAcrossWithdrawResubmitWithdraw() {
        // 验收基准：提交→撤回→重报→撤回 两轮后总行数恒等于初始（不再出现第三、四行）
        AuditInstance instance = inReviewMutationInstance(1L, "batch-99", 2001L);
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        cancelWorkflowUseCase.withdrawToRequesterBatch(List.of(instance));
        assertEquals(2, instance.getStepInstances().size());

        // 填报人重新发起（resume 复用同链，零新增行）
        instance.reactivateWithdrawnStep();
        assertEquals(AuditInstance.STATUS_PENDING, instance.getStatus());
        assertEquals(2, instance.getStepInstances().size());
        assertEquals(AuditInstance.STEP_STATUS_PENDING, instance.getStepInstances().get(1).getStatus());

        // 第二轮撤回：行数仍恒等于初始
        cancelWorkflowUseCase.withdrawToRequesterBatch(List.of(instance));
        assertEquals(AuditInstance.STATUS_WITHDRAWN, instance.getStatus());
        assertNull(instance.getCompletedAt());
        assertEquals(2, instance.getStepInstances().size());
        assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, instance.getStepInstances().get(0).getStatus());
        assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, instance.getStepInstances().get(1).getStatus());
    }
}
