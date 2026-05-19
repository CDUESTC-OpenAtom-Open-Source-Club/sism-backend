package com.sism.strategy.domain.plan.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Plan Domain Event Tests")
class PlanEventTest {

    @Test
    @DisplayName("PlanCreatedEvent should expose stable event metadata")
    void shouldExposeStablePlanCreatedEventMetadata() {
        PlanCreatedEvent event = new PlanCreatedEvent(11L, "STRAT_TO_FUNC", 36L);

        String eventId = event.getEventId();
        LocalDateTime occurredOn = event.getOccurredOn();

        assertEquals(eventId, event.getEventId());
        assertEquals(occurredOn, event.getOccurredOn());
        assertEquals(11L, event.getPlanId());
        assertEquals("STRAT_TO_FUNC", event.getPlanLevel());
        assertEquals(36L, event.getTargetOrgId());
    }

    @Test
    @DisplayName("PlanSubmittedForApprovalEvent should expose stable event metadata")
    void shouldExposeStablePlanSubmittedEventMetadata() {
        PlanSubmittedForApprovalEvent event = new PlanSubmittedForApprovalEvent(
                22L,
                "PLAN_APPROVAL_FUNCDEPT",
                188L,
                36L,
                "提交说明"
        );

        String eventId = event.getEventId();
        LocalDateTime occurredOn = event.getOccurredOn();

        assertEquals(eventId, event.getEventId());
        assertEquals(occurredOn, event.getOccurredOn());
        assertEquals(22L, event.getPlanId());
        assertEquals("PLAN_APPROVAL_FUNCDEPT", event.getWorkflowCode());
        assertEquals(188L, event.getSubmitterId());
        assertEquals(36L, event.getSubmitterOrgId());
    }

    @Test
    @DisplayName("PlanStatusChangedEvent should expose stable event metadata")
    void shouldExposeStablePlanStatusChangedEventMetadata() {
        PlanStatusChangedEvent event = new PlanStatusChangedEvent(33L, "DRAFT", "PENDING");

        String eventId = event.getEventId();
        LocalDateTime occurredOn = event.getOccurredOn();

        assertEquals(eventId, event.getEventId());
        assertEquals(occurredOn, event.getOccurredOn());
        assertEquals(33L, event.getPlanId());
        assertEquals("DRAFT", event.getOldStatus());
        assertEquals("PENDING", event.getNewStatus());
    }
}
