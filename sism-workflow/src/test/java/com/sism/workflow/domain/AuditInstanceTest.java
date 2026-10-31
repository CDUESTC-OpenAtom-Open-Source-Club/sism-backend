package com.sism.workflow.domain;

import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuditInstance Aggregate Root Tests")
class AuditInstanceTest {

    @Test
    @DisplayName("Should create AuditInstance with valid parameters")
    void shouldCreateAuditInstanceWithValidParameters() {
        AuditFlowDef flowDef = buildFlowDef();

        AuditInstance instance = AuditInstance.create(1L, "INDICATOR", flowDef);

        assertNotNull(instance);
        assertEquals(1L, instance.getEntityId());
        assertEquals("INDICATOR", instance.getEntityType());
        assertEquals(AuditInstance.STATUS_PENDING, instance.getStatus());
        assertNotNull(instance.getStartedAt());
    }

    @Test
    @DisplayName("Should throw exception when creating AuditInstance with invalid entity id")
    void shouldThrowExceptionWhenCreatingAuditInstanceWithInvalidEntityId() {
        AuditFlowDef flowDef = buildFlowDef();

        assertThrows(IllegalArgumentException.class, () ->
            AuditInstance.create(-1L, "INDICATOR", flowDef)
        );
    }

    @Test
    @DisplayName("Should validate AuditInstance with valid parameters")
    void shouldValidateAuditInstanceWithValidParameters() {
        AuditFlowDef flowDef = buildFlowDef();
        AuditInstance instance = AuditInstance.create(1L, "INDICATOR", flowDef);

        assertDoesNotThrow(instance::validate);
    }

    @Test
    @DisplayName("Should resolve current pending step from step instances instead of currentStepIndex")
    void shouldResolveCurrentPendingStepFromStepInstances() {
        AuditInstance instance = new AuditInstance();

        AuditStepInstance waitingStep = new AuditStepInstance();
        waitingStep.setStepNo(1);
        waitingStep.setStatus(AuditInstance.STEP_STATUS_WAITING);

        AuditStepInstance pendingStep = new AuditStepInstance();
        pendingStep.setStepNo(2);
        pendingStep.setStatus(AuditInstance.STEP_STATUS_PENDING);

        instance.addStepInstance(waitingStep);
        instance.addStepInstance(pendingStep);

        assertEquals(2, instance.resolveCurrentPendingStep().orElseThrow().getStepNo());
    }

    @Test
    @DisplayName("Should resolve latest pending step when multiple pending instances exist in history")
    void shouldResolveLatestPendingStepWhenMultiplePendingInstancesExistInHistory() {
        AuditInstance instance = new AuditInstance();

        AuditStepInstance earlierPendingStep = new AuditStepInstance();
        earlierPendingStep.setStepNo(2);
        earlierPendingStep.setStatus(AuditInstance.STEP_STATUS_PENDING);

        AuditStepInstance latestPendingStep = new AuditStepInstance();
        latestPendingStep.setStepNo(4);
        latestPendingStep.setStatus(AuditInstance.STEP_STATUS_PENDING);

        instance.addStepInstance(earlierPendingStep);
        instance.addStepInstance(latestPendingStep);

        assertEquals(4, instance.resolveCurrentPendingStep().orElseThrow().getStepNo());
    }

    @Test
    @DisplayName("Should allow requester withdraw after submit step auto-completed but before first approval handled")
    void shouldAllowRequesterWithdrawBeforeFirstApprovalHandled() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);

        AuditStepInstance firstApproval = new AuditStepInstance();
        firstApproval.setStepNo(2);
        firstApproval.setStatus(AuditInstance.STEP_STATUS_PENDING);

        instance.addStepInstance(submitStep);
        instance.addStepInstance(firstApproval);

        assertTrue(instance.canRequesterWithdraw());
        assertDoesNotThrow(instance::cancel);
        assertEquals(AuditInstance.STATUS_WITHDRAWN, instance.getStatus());
        assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, submitStep.getStatus());
        assertEquals("提交人撤回", submitStep.getComment());
        assertNotNull(submitStep.getApprovedAt());
        assertEquals(AuditInstance.STEP_STATUS_WAITING, firstApproval.getStatus());
        assertNull(firstApproval.getComment());
        assertNull(firstApproval.getApprovedAt());
    }

    @Test
    @DisplayName("Should forbid requester withdraw after first approval handled")
    void shouldForbidRequesterWithdrawAfterFirstApprovalHandled() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);

        AuditStepInstance firstApproval = new AuditStepInstance();
        firstApproval.setStepNo(2);
        firstApproval.setStatus(AuditInstance.STEP_STATUS_APPROVED);

        AuditStepInstance secondApproval = new AuditStepInstance();
        secondApproval.setStepNo(3);
        secondApproval.setStatus(AuditInstance.STEP_STATUS_PENDING);

        instance.addStepInstance(submitStep);
        instance.addStepInstance(firstApproval);
        instance.addStepInstance(secondApproval);

        assertFalse(instance.canRequesterWithdraw());
        assertThrows(IllegalStateException.class, instance::cancel);
    }

    @Test
    @DisplayName("Should keep rejected step history instead of reopening previous step in place")
    void shouldKeepRejectedStepHistoryInsteadOfReopeningPreviousStepInPlace() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStepDefId(11L);
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);

        AuditStepInstance departmentStep = new AuditStepInstance();
        departmentStep.setStepNo(2);
        departmentStep.setStepDefId(12L);
        departmentStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);

        AuditStepInstance leaderStep = new AuditStepInstance();
        leaderStep.setStepNo(3);
        leaderStep.setStepDefId(13L);
        leaderStep.setStatus(AuditInstance.STEP_STATUS_PENDING);

        instance.addStepInstance(submitStep);
        instance.addStepInstance(departmentStep);
        instance.addStepInstance(leaderStep);

        instance.reject(9L, "打回");

        assertEquals(AuditInstance.STEP_STATUS_APPROVED, departmentStep.getStatus());
        assertEquals(AuditInstance.STEP_STATUS_REJECTED, leaderStep.getStatus());
        assertEquals(3, instance.getStepInstances().size());
    }

    // ==================== 2026-10-30 取消异动（退回待重报整链终结） ====================

    @Test
    @DisplayName("cancelReturned: 退回待重报的异动链可被发起人终结为 CANCELLED 且 completedAt=now")
    void shouldCancelReturnedMutationChainIntoCancelledTerminalState() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_WITHDRAWN);
        instance.setRequesterId(100L);
        instance.setCompletedAt(null);

        AuditStepInstance approvedSubmit = new AuditStepInstance();
        approvedSubmit.setStepNo(1);
        approvedSubmit.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        approvedSubmit.setApproverId(100L);

        AuditStepInstance rejectedApproval = new AuditStepInstance();
        rejectedApproval.setStepNo(2);
        rejectedApproval.setStatus(AuditInstance.STEP_STATUS_REJECTED);
        rejectedApproval.setApproverId(300L);

        // 驳回后退回填报人的提交步骤（RejectWorkflowUseCase.createReturnedStep 产物）
        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setApproverId(100L);
        returnedSubmit.setComment("驳回后退回填报人重新提交");

        instance.addStepInstance(approvedSubmit);
        instance.addStepInstance(rejectedApproval);
        instance.addStepInstance(returnedSubmit);

        instance.cancelReturned();

        assertEquals(AuditInstance.STATUS_CANCELLED, instance.getStatus());
        assertNotNull(instance.getCompletedAt());
        // 退回提交步骤保持原样留痕
        assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, returnedSubmit.getStatus());
        assertEquals("驳回后退回填报人重新提交", returnedSubmit.getComment());
    }

    @Test
    @DisplayName("cancelReturned: 退回提交步骤按步骤名含「提交」识别（approverId 未指派的历史数据）")
    void shouldRecognizeReturnedSubmitStepByName() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_WITHDRAWN);

        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStepName("填报人提交");
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);

        instance.addStepInstance(returnedSubmit);

        assertDoesNotThrow(instance::cancelReturned);
        assertEquals(AuditInstance.STATUS_CANCELLED, instance.getStatus());
    }

    @Test
    @DisplayName("cancelReturned: 非退回待重报状态（IN_REVIEW）拒绝取消")
    void shouldForbidCancelReturnedWhenNotWithdrawn() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setApproverId(100L);
        instance.addStepInstance(returnedSubmit);

        assertThrows(IllegalStateException.class, instance::cancelReturned);
        assertEquals(AuditInstance.STATUS_PENDING, instance.getStatus());
    }

    @Test
    @DisplayName("cancelReturned: 已完结的 WITHDRAWN（发起人审批中撤回，completedAt 非空）拒绝取消")
    void shouldForbidCancelReturnedWhenAlreadyCompleted() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_WITHDRAWN);
        instance.setCompletedAt(java.time.LocalDateTime.now());

        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setApproverId(100L);
        instance.addStepInstance(returnedSubmit);

        assertThrows(IllegalStateException.class, instance::cancelReturned);
    }

    @Test
    @DisplayName("cancelReturned: 无退回提交步骤（中间节点退回待审）拒绝取消")
    void shouldForbidCancelReturnedWithoutReturnedSubmitStep() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_WITHDRAWN);
        instance.setRequesterId(100L);

        // 只有审批人步骤被退回（WITHDRAWN 但不是提交步骤），链条仍由审批人持有
        AuditStepInstance returnedApproval = new AuditStepInstance();
        returnedApproval.setStepNo(3);
        returnedApproval.setStepName("战略发展部负责人审批");
        returnedApproval.setStatus(AuditInstance.STEP_STATUS_PENDING);
        returnedApproval.setApproverId(300L);
        instance.addStepInstance(returnedApproval);

        assertThrows(IllegalStateException.class, instance::cancelReturned);
        assertEquals(AuditInstance.STATUS_WITHDRAWN, instance.getStatus());
    }

    // ==================== 2026-10-30 异动撤回（退回填报人活链） ====================

    @Test
    @DisplayName("withdrawToRequester: 发起人撤回把链退回填报人且保持存活（WITHDRAWN、completedAt 为空）")
    void shouldWithdrawToRequesterKeepingChainAlive() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setRequesterId(100L);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStepName("填报人提交");
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitStep.setApproverId(100L);

        AuditStepInstance currentApproval = new AuditStepInstance();
        currentApproval.setStepNo(2);
        currentApproval.setStepName("战略发展部负责人审批");
        currentApproval.setStatus(AuditInstance.STEP_STATUS_PENDING);
        currentApproval.setApproverId(300L);

        instance.addStepInstance(submitStep);
        instance.addStepInstance(currentApproval);

        instance.withdrawToRequester();

        // 链保持存活：实例 WITHDRAWN 但未完结（不进审批历史）
        assertEquals(AuditInstance.STATUS_WITHDRAWN, instance.getStatus());
        assertNull(instance.getCompletedAt());
        // 当前在审步骤被撤回留痕
        assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, currentApproval.getStatus());
        assertEquals("提交人撤回", currentApproval.getComment());
        assertNotNull(currentApproval.getApprovedAt());
        // 已走完的提交步骤保持原样
        assertEquals(AuditInstance.STEP_STATUS_APPROVED, submitStep.getStatus());
    }

    @Test
    @DisplayName("withdrawToRequester: 非审批中（已退回待重报）拒绝撤回")
    void shouldForbidWithdrawToRequesterWhenNotInReview() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_WITHDRAWN);
        instance.setCompletedAt(null);

        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setApproverId(100L);
        instance.addStepInstance(returnedSubmit);

        assertThrows(IllegalStateException.class, instance::withdrawToRequester);
    }

    @Test
    @DisplayName("withdrawToRequester: 审批中但无在审步骤拒绝撤回")
    void shouldForbidWithdrawToRequesterWithoutPendingStep() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        instance.addStepInstance(submitStep);

        assertThrows(IllegalStateException.class, instance::withdrawToRequester);
    }

    @Test
    @DisplayName("发起人撤回的链形（在审步骤 WITHDRAWN+退回提交步骤）重新提交后审批环节复活为 PENDING")
    void shouldReviveWithdrawnApprovalStepOnResubmitAfterRequesterWithdraw() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_PENDING);
        instance.setRequesterId(100L);
        instance.setRequesterOrgId(57L);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStepDefId(11L);
        submitStep.setStepName("填报人提交");
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitStep.setApproverId(100L);

        AuditStepInstance currentApproval = new AuditStepInstance();
        currentApproval.setStepNo(2);
        currentApproval.setStepDefId(12L);
        currentApproval.setStepName("战略发展部负责人审批");
        currentApproval.setStatus(AuditInstance.STEP_STATUS_PENDING);
        currentApproval.setApproverId(300L);
        currentApproval.setApproverOrgId(35L);

        instance.addStepInstance(submitStep);
        instance.addStepInstance(currentApproval);

        // 发起人撤回（AuditInstance.withdrawToRequester）+ 用例层追加退回提交步骤
        instance.withdrawToRequester();
        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(instance.nextStepInstanceNo());
        returnedSubmit.setStepDefId(11L);
        returnedSubmit.setStepName("填报人提交");
        returnedSubmit.setApproverId(100L);
        returnedSubmit.setApproverOrgId(57L);
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setComment("提交人撤回");
        instance.addStepInstance(returnedSubmit);
        assertEquals(AuditInstance.STATUS_WITHDRAWN, instance.getStatus());
        assertNull(instance.getCompletedAt());

        // 填报人重新发起：既有 resume 机制复用同链
        instance.reactivateWithdrawnStep();

        assertEquals(AuditInstance.STATUS_PENDING, instance.getStatus());
        assertNull(instance.getCompletedAt());
        // 退回提交步骤转为 APPROVED（提交留痕）
        assertEquals(AuditInstance.STEP_STATUS_APPROVED, returnedSubmit.getStatus());
        // 被撤回的审批环节复活为 PENDING，审批人继续审（无 WAITING/REJECTED 可复用的链形专用品）
        assertEquals(AuditInstance.STEP_STATUS_PENDING, currentApproval.getStatus());
        assertNull(currentApproval.getComment());
        assertNull(currentApproval.getApprovedAt());
        assertEquals(2, instance.resolveCurrentPendingStep().orElseThrow().getStepNo());
    }

    @Test
    @DisplayName("Should reactivate latest withdrawn step when submitter starts workflow again")
    void shouldReactivateLatestWithdrawnStep() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_WITHDRAWN);

        AuditStepInstance withdrawnStep = new AuditStepInstance();
        withdrawnStep.setStepNo(1);
        withdrawnStep.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        withdrawnStep.setComment("提交人撤回");
        withdrawnStep.setApprovedAt(java.time.LocalDateTime.now());

        AuditStepInstance waitingApproval = new AuditStepInstance();
        waitingApproval.setStepNo(2);
        waitingApproval.setStatus(AuditInstance.STEP_STATUS_WAITING);

        instance.addStepInstance(withdrawnStep);
        instance.addStepInstance(waitingApproval);

        instance.reactivateWithdrawnStep();

        assertEquals(AuditInstance.STATUS_PENDING, instance.getStatus());
        assertEquals(AuditInstance.STEP_STATUS_APPROVED, withdrawnStep.getStatus());
        assertEquals("提交人重新提交（未填写说明）", withdrawnStep.getComment());
        assertNotNull(withdrawnStep.getApprovedAt());
        assertEquals(AuditInstance.STEP_STATUS_PENDING, waitingApproval.getStatus());
        assertEquals(2, instance.resolveCurrentPendingStep().orElseThrow().getStepNo());
    }

    @Test
    @DisplayName("Should reactivate withdrawn submit step from rejected legacy instance")
    void shouldReactivateWithdrawnStepFromRejectedLegacyInstance() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_REJECTED);
        instance.setRequesterId(100L);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);

        AuditStepInstance rejectedApproval = new AuditStepInstance();
        rejectedApproval.setStepNo(2);
        rejectedApproval.setStatus(AuditInstance.STEP_STATUS_REJECTED);

        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setComment("驳回后退回填报人重新提交");
        returnedSubmit.setApproverId(100L);

        AuditStepInstance waitingApproval = new AuditStepInstance();
        waitingApproval.setStepNo(4);
        waitingApproval.setStatus(AuditInstance.STEP_STATUS_WAITING);

        instance.addStepInstance(submitStep);
        instance.addStepInstance(rejectedApproval);
        instance.addStepInstance(returnedSubmit);
        instance.addStepInstance(waitingApproval);

        instance.reactivateWithdrawnStep();

        assertEquals(AuditInstance.STATUS_PENDING, instance.getStatus());
        assertEquals(AuditInstance.STEP_STATUS_APPROVED, returnedSubmit.getStatus());
        assertEquals("提交人重新提交（未填写说明）", returnedSubmit.getComment());
        assertEquals(AuditInstance.STEP_STATUS_PENDING, waitingApproval.getStatus());
        assertEquals(4, instance.resolveCurrentPendingStep().orElseThrow().getStepNo());
    }

    @Test
    @DisplayName("Should create replay approval step on resubmit when reject flow did not precreate waiting step")
    void shouldCreateReplayApprovalStepOnResubmitWithoutWaitingStep() {
        AuditInstance instance = new AuditInstance();
        instance.setStatus(AuditInstance.STATUS_REJECTED);
        instance.setRequesterId(100L);
        instance.setRequesterOrgId(57L);

        AuditStepInstance submitStep = new AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStepDefId(11L);
        submitStep.setStepName("填报人提交");
        submitStep.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitStep.setApproverId(100L);
        submitStep.setApproverOrgId(57L);

        AuditStepInstance rejectedApproval = new AuditStepInstance();
        rejectedApproval.setStepNo(2);
        rejectedApproval.setStepDefId(12L);
        rejectedApproval.setStepName("职能部门审批人审批");
        rejectedApproval.setStatus(AuditInstance.STEP_STATUS_REJECTED);
        rejectedApproval.setApproverOrgId(88L);

        AuditStepInstance returnedSubmit = new AuditStepInstance();
        returnedSubmit.setStepNo(3);
        returnedSubmit.setStepDefId(11L);
        returnedSubmit.setStepName("填报人提交");
        returnedSubmit.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedSubmit.setComment("驳回后退回填报人重新提交");
        returnedSubmit.setApproverId(100L);
        returnedSubmit.setApproverOrgId(57L);

        instance.addStepInstance(submitStep);
        instance.addStepInstance(rejectedApproval);
        instance.addStepInstance(returnedSubmit);

        instance.reactivateWithdrawnStep();

        assertEquals(AuditInstance.STATUS_PENDING, instance.getStatus());
        assertEquals(AuditInstance.STEP_STATUS_APPROVED, returnedSubmit.getStatus());
        assertEquals(4, instance.getStepInstances().size());

        AuditStepInstance replayStep = instance.getStepInstances().get(3);
        assertEquals(AuditInstance.STEP_STATUS_PENDING, replayStep.getStatus());
        assertEquals(12L, replayStep.getStepDefId());
        assertEquals("职能部门审批人审批", replayStep.getStepName());
        assertEquals(88L, replayStep.getApproverOrgId());
        assertNull(replayStep.getApproverId());
    }

    private AuditFlowDef buildFlowDef() {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(1L);
        flowDef.setFlowCode("FLOW_001");
        flowDef.setFlowName("测试流程");
        flowDef.setEntityType("INDICATOR");
        flowDef.setIsActive(true);
        return flowDef;
    }
}
