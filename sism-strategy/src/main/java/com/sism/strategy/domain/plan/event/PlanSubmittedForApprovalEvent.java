package com.sism.strategy.domain.plan.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sism.shared.domain.model.base.DomainEvent;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Event raised when a plan is submitted and should start a workflow instance.
 */
@Getter
public class PlanSubmittedForApprovalEvent implements DomainEvent {

    private final String eventId;
    private final LocalDateTime occurredOn;
    private final Long planId;
    private final String workflowCode;
    private final Long submitterId;
    private final Long submitterOrgId;
    private final String comment;

    public PlanSubmittedForApprovalEvent(Long planId,
                                         String workflowCode,
                                         Long submitterId,
                                         Long submitterOrgId,
                                         String comment) {
        this(UUID.randomUUID().toString(), LocalDateTime.now(), planId, workflowCode, submitterId, submitterOrgId, comment);
    }

    @JsonCreator
    public PlanSubmittedForApprovalEvent(@JsonProperty("eventId") String eventId,
                                         @JsonProperty("occurredOn") LocalDateTime occurredOn,
                                         @JsonProperty("planId") Long planId,
                                         @JsonProperty("workflowCode") String workflowCode,
                                         @JsonProperty("submitterId") Long submitterId,
                                         @JsonProperty("submitterOrgId") Long submitterOrgId,
                                         @JsonProperty("comment") String comment) {
        this.eventId = eventId;
        this.occurredOn = occurredOn;
        this.planId = planId;
        this.workflowCode = workflowCode;
        this.submitterId = submitterId;
        this.submitterOrgId = submitterOrgId;
        this.comment = comment;
    }

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    public LocalDateTime getOccurredOn() {
        return occurredOn;
    }

    @Override
    public String getEventType() {
        return "PlanSubmittedForApprovalEvent";
    }
}
