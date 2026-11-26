package com.sism.workflow.application.runtime;

import com.sism.exception.BusinessConflictException;
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
 * 2026-10-30 二次定案（异动撤回零新增行）：撤回不再追加「退回提交步骤」行，
 * 提交步由领域层原地翻转 WITHDRAWN，链位回到填报人。
 * 2026-11-26（撤回窗口收窄，用户定调最初设计）：撤回窗口=提交后→第一个审批
 * 环节审核前（学院线=学院审核人审批 / 职能线=职能部门审批人审批），环节推进
 * 后撤回 409 业务冲突且文案带当前环节。
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

    // ==================== 2026-11-26 撤回环节门（撤回窗口=第一审批环节审核前） ====================

    /**
     * 构造一条学院线「上报审批中」实例：填报人提交步 APPROVED，其后审批步按传入
     * 状态铺开（第一环节=学院审核人审批，第二环节=分管职能部门负责人审批）。
     */
    private AuditInstance inReviewReportInstance(String firstApprovalStatus, String secondApprovalStatus) {
        AuditInstance instance = new AuditInstance();
        instance.setId(20L);
        instance.setEntityType("PLAN_REPORT");
        instance.setEntityId(3L);
        instance.setRequesterId(370L);
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setCompletedAt(null);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStepName("填报人提交");
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitStep.setApproverId(370L);
        submitStep.setApprovedAt(java.time.LocalDateTime.now().minusHours(1));
        instance.addStepInstance(submitStep);

        AuditStepInstance firstApproval = new AuditStepInstance();
        firstApproval.setStepNo(2);
        firstApproval.setStepName("学院审核人审批");
        firstApproval.setStatus(firstApprovalStatus);
        firstApproval.setApproverId(371L);
        if (AuditInstance.STEP_STATUS_APPROVED.equals(firstApprovalStatus)) {
            firstApproval.setApprovedAt(java.time.LocalDateTime.now().minusMinutes(30));
        }
        instance.addStepInstance(firstApproval);

        if (secondApprovalStatus != null) {
            AuditStepInstance secondApproval = new AuditStepInstance();
            secondApproval.setStepNo(3);
            secondApproval.setStepName("分管职能部门负责人审批");
            secondApproval.setStatus(secondApprovalStatus);
            secondApproval.setApproverId(380L);
            instance.addStepInstance(secondApproval);
        }
        return instance;
    }

    @Test
    void cancel_shouldAllowWithdrawWhileFirstApprovalStagePending() {
        // 第一环节（学院审核人审批）未审：窗口开着，撤回照常（原语义保留）
        AuditInstance instance = inReviewReportInstance(
                AuditInstance.STEP_STATUS_PENDING, null);
        when(auditInstanceRepository.save(any(AuditInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuditInstance saved = cancelWorkflowUseCase.cancel(instance);

        assertEquals(AuditInstance.STATUS_WITHDRAWN, saved.getStatus());
        assertNotNull(saved.getCompletedAt());
        verify(auditInstanceRepository, times(1)).save(any(AuditInstance.class));
        // publishEvent 有 ApplicationEvent / Object 两个重载，事件走 Object 通道
        verify(eventPublisher, times(1))
                .publishEvent(any(com.sism.shared.domain.notification.WorkflowChainWithdrawnEvent.class));
    }

    @Test
    void cancel_shouldRejectWithStageAwareConflictWhenFirstApprovalApproved() {
        // 学院审核人已通过、推进到「分管职能部门负责人审批」：撤回窗口关闭 →
        // 409 业务冲突（BusinessConflictException），文案带当前环节
        AuditInstance instance = inReviewReportInstance(
                AuditInstance.STEP_STATUS_APPROVED, AuditInstance.STEP_STATUS_PENDING);

        BusinessConflictException conflict = assertThrows(BusinessConflictException.class,
                () -> cancelWorkflowUseCase.cancel(instance));

        assertTrue(conflict.getMessage().contains("分管职能部门负责人审批"),
                "文案应带当前环节，实际：" + conflict.getMessage());
        assertTrue(conflict.getMessage().contains("不可撤回"));
        // 窗口关闭时不得落库/广播（事务回滚前置失败）
        verify(auditInstanceRepository, never()).save(any(AuditInstance.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancel_shouldKeepOriginalIllegalStateWhenInstanceNotInReview() {
        // 全部审批通过（已完结）：不在审批中，保留领域层原 IllegalStateException 语义
        AuditInstance instance = inReviewReportInstance(
                AuditInstance.STEP_STATUS_APPROVED, AuditInstance.STEP_STATUS_APPROVED);
        instance.setStatus(AuditInstance.STATUS_APPROVED);
        instance.setCompletedAt(java.time.LocalDateTime.now().minusMinutes(5));

        IllegalStateException illegalState = assertThrows(IllegalStateException.class,
                () -> cancelWorkflowUseCase.cancel(instance));

        assertEquals("Cannot cancel: first approval step has already been handled", illegalState.getMessage());
        verify(auditInstanceRepository, never()).save(any(AuditInstance.class));
    }

    @Test
    void cancel_shouldRejectWithStageAwareConflictOnFunctionalDeptChainToo() {
        // 职能部门线同口径：职能部门审批人已通过 → 撤回 409 且文案带当前环节
        AuditInstance instance = inReviewReportInstance(
                AuditInstance.STEP_STATUS_APPROVED, AuditInstance.STEP_STATUS_PENDING);
        instance.getStepInstances().get(1).setStepName("职能部门审批人审批");
        instance.getStepInstances().get(2).setStepName("分管校领导审批");

        BusinessConflictException conflict = assertThrows(BusinessConflictException.class,
                () -> cancelWorkflowUseCase.cancel(instance));

        assertTrue(conflict.getMessage().contains("分管校领导审批"),
                "文案应带当前环节，实际：" + conflict.getMessage());
        verify(auditInstanceRepository, never()).save(any(AuditInstance.class));
    }
}
