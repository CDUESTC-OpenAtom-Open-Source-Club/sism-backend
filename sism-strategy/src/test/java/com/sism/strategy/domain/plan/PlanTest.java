package com.sism.strategy.domain.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Plan Aggregate Root Tests")
class PlanTest {

    @Test
    @DisplayName("Should create Plan with valid parameters")
    void shouldCreatePlanWithValidParameters() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);

        assertNotNull(plan);
        assertEquals(1L, plan.getCycleId());
        assertEquals(1L, plan.getTargetOrgId());
        assertEquals(1L, plan.getCreatedByOrgId());
        assertEquals(PlanLevel.COMPREHENSIVE, plan.getPlanLevel());
        assertEquals("DRAFT", plan.getStatus());
        assertFalse(plan.getIsDeleted());
        assertNotNull(plan.getCreatedAt());
        assertNotNull(plan.getUpdatedAt());
    }

    @Test
    @DisplayName("Should throw exception when creating Plan with null cycle id")
    void shouldThrowExceptionWhenCreatingPlanWithNullCycleId() {
        assertThrows(IllegalArgumentException.class, () ->
            Plan.create(null, 1L, 1L, PlanLevel.COMPREHENSIVE)
        );
    }

    @Test
    @DisplayName("Should activate Plan successfully")
    void shouldActivatePlanSuccessfully() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);

        plan.activate();

        assertEquals("DISTRIBUTED", plan.getStatus());
        assertNotNull(plan.getUpdatedAt());
    }

    @Test
    @DisplayName("Should promote DRAFT plan on indicator distributed and stay idempotent")
    void shouldPromoteDraftPlanOnIndicatorDistributed() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.STRAT_TO_FUNC);

        assertTrue(plan.promoteFromDraftOnIndicatorDistributed());
        assertEquals("DISTRIBUTED", plan.getStatus());

        // 幂等：已 DISTRIBUTED 时再次调用返回 false 且不抛异常
        assertFalse(plan.promoteFromDraftOnIndicatorDistributed());
        assertEquals("DISTRIBUTED", plan.getStatus());
    }

    @Test
    @DisplayName("Should not promote PENDING plan on indicator distributed")
    void shouldNotPromotePendingPlanOnIndicatorDistributed() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.STRAT_TO_FUNC);
        plan.submitForApproval();

        assertFalse(plan.promoteFromDraftOnIndicatorDistributed());
        assertEquals("PENDING", plan.getStatus());
    }

    @Test
    @DisplayName("Should allow plan approval submission only in draft or returned state")
    void shouldAllowPlanApprovalSubmissionOnlyInDraftOrReturnedState() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);
        assertDoesNotThrow(() -> plan.ensureCanSubmitForApproval());

        plan.returnForRevision();
        assertDoesNotThrow(() -> plan.ensureCanSubmitForApproval());
    }

    @Test
    @DisplayName("Should reject approval submission from non-draft state with business-friendly Chinese message")
    void shouldRejectApprovalSubmissionFromNonDraftStateWithChineseMessage() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);
        plan.submitForApproval();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> plan.ensureCanSubmitForApproval());

        assertTrue(error.getMessage().contains("当前计划状态不支持发起下发审批"),
                "状态类异常必须是业务化中文消息: " + error.getMessage());
        assertTrue(error.getMessage().contains("仅草稿或退回待重报状态可发起"),
                "需说明仅草稿或退回待重报状态可发起: " + error.getMessage());
    }

    @Test
    @DisplayName("Should move plan to pending when submitted for approval")
    void shouldMovePlanToPendingWhenSubmittedForApproval() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);

        plan.submitForApproval();

        assertEquals("PENDING", plan.getStatus());
        assertNotNull(plan.getUpdatedAt());
        assertFalse(plan.isEditable());
    }

    @Test
    @DisplayName("Should allow distributed plan submission when distributed state is explicitly allowed")
    void shouldAllowDistributedPlanSubmissionWhenExplicitlyAllowed() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);
        plan.activate();

        assertDoesNotThrow(() -> plan.ensureCanSubmitForApproval(true));

        plan.submitForApproval(true);

        assertEquals("PENDING", plan.getStatus());
        assertNotNull(plan.getUpdatedAt());
    }

    @Test
    @DisplayName("Should approve Plan to distributed state")
    void shouldApprovePlanToDistributedState() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);
        plan.approve();

        assertEquals("DISTRIBUTED", plan.getStatus());
        assertNotNull(plan.getUpdatedAt());
    }

    @Test
    @DisplayName("Should move Plan to returned when rejected")
    void shouldMovePlanToReturnedWhenRejected() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);
        plan.returnForRevision();

        assertEquals("RETURNED", plan.getStatus());
        assertTrue(plan.isReturned());
        assertTrue(plan.isEditable());
        assertNotNull(plan.getUpdatedAt());
    }

    @Test
    @DisplayName("Should withdraw Plan back to draft")
    void shouldWithdrawPlanBackToDraft() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);
        plan.activate();

        plan.withdraw();

        assertEquals("DRAFT", plan.getStatus());
    }

    @Test
    @DisplayName("Should withdraw approval Plan back to draft")
    void shouldWithdrawApprovalPlanBackToDraft() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);
        plan.submitForApproval();

        plan.withdraw();

        assertEquals("DRAFT", plan.getStatus());
        assertNotNull(plan.getUpdatedAt());
    }

    @Test
    @DisplayName("Should validate Plan with valid parameters")
    void shouldValidatePlanWithValidParameters() {
        Plan plan = Plan.create(1L, 1L, 1L, PlanLevel.COMPREHENSIVE);

        assertDoesNotThrow(plan::validate);
    }

    @Test
    @DisplayName("Should validate Plan with invalid parameters")
    void shouldValidatePlanWithInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> {
            Plan.create(null, null, null, null);
        });
    }
}
