package com.sism.workflow.domain.runtime;

import com.sism.shared.domain.model.base.AggregateRoot;
import com.sism.workflow.domain.definition.AuditFlowDef;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * AuditInstance - 审批实例聚合根
 * Represents an active approval workflow instance.
 */
@Getter
@Setter
@Entity(name = "WorkflowAuditInstance")
@Table(name = "audit_instance")
@Where(clause = "is_deleted = false")
@Access(AccessType.FIELD)
public class AuditInstance extends AggregateRoot<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public static final String STATUS_PENDING = "IN_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_RETURNED = "RETURNED";
    public static final String STEP_STATUS_PENDING = "PENDING";
    public static final String STEP_STATUS_WAITING = "WAITING";
    public static final String STEP_STATUS_APPROVED = "APPROVED";
    public static final String STEP_STATUS_REJECTED = "REJECTED";
    public static final String STEP_STATUS_WITHDRAWN = "WITHDRAWN";

    @Column(name = "flow_def_id")
    private Long flowDefId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "requester_id")
    private Long requesterId;

    @Column(name = "requester_org_id")
    private Long requesterOrgId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @OneToMany(mappedBy = "instance", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepNo ASC")
    private List<AuditStepInstance> stepInstances = new ArrayList<>();

    public static AuditInstance create(Long entityId, String entityType, AuditFlowDef flowDef) {
        if (entityId == null || entityId <= 0) {
            throw new IllegalArgumentException("Entity ID must be positive");
        }
        if (entityType == null || entityType.trim().isEmpty()) {
            throw new IllegalArgumentException("Entity type is required");
        }
        if (flowDef == null) {
            throw new IllegalArgumentException("Flow definition is required");
        }

        AuditInstance instance = new AuditInstance();
        instance.setEntityId(entityId);
        instance.setEntityType(entityType);
        instance.setFlowDefId(flowDef.getId());
        instance.setStatus(STATUS_PENDING);
        instance.setStartedAt(LocalDateTime.now());
        return instance;
    }

    @Override
    public boolean canPublish() {
        return STATUS_APPROVED.equals(status) || STATUS_REJECTED.equals(status);
    }

    @Override
    public void validate() {
        if (entityType == null || entityType.trim().isEmpty()) {
            throw new IllegalArgumentException("Entity type is required");
        }
        if (entityId == null) {
            throw new IllegalArgumentException("Entity ID is required");
        }
    }

    public void approve(Long userId, String comment) {
        approve(userId, comment, null);
    }

    /**
     * 审批通过并留痕鉴定进度等级（P1 上报链改造）。
     *
     * @param appraisalLevel 本节点鉴定等级 AHEAD/NORMAL/DELAYED，可为空（非鉴定场景）
     */
    public void approve(Long userId, String comment, String appraisalLevel) {
        if (!STATUS_PENDING.equals(status)) {
            throw new IllegalStateException("Cannot approve: workflow is not in review");
        }
        if (stepInstances == null || stepInstances.isEmpty()) {
            this.status = STATUS_APPROVED;
            this.completedAt = LocalDateTime.now();
            return;
        }

        AuditStepInstance current = resolveCurrentPendingStep()
                .orElseThrow(() -> new IllegalStateException("No pending step available for approval"));

        current.setApproverId(userId);
        current.setStatus(STEP_STATUS_APPROVED);
        current.setComment(comment);
        if (appraisalLevel != null && !appraisalLevel.isBlank()) {
            current.setAppraisalLevel(appraisalLevel);
        }
        current.setApprovedAt(LocalDateTime.now());

        // 不再查找WAITING步骤，由ApproveWorkflowUseCase动态创建下一步
        // 如果没有更多步骤会被创建，则标记为已完成
    }

    public void reject(Long userId, String comment) {
        if (!STATUS_PENDING.equals(status)) {
            throw new IllegalStateException("Cannot reject: workflow is not in review");
        }
        if (stepInstances == null || stepInstances.isEmpty()) {
            this.status = STATUS_REJECTED;
            this.completedAt = LocalDateTime.now();
            return;
        }

        AuditStepInstance current = resolveCurrentPendingStep()
                .orElseThrow(() -> new IllegalStateException("No pending step available for rejection"));
        current.setApproverId(userId);
        current.setStatus(STEP_STATUS_REJECTED);
        current.setComment(comment);
        current.setApprovedAt(LocalDateTime.now());
        this.completedAt = null;
    }

    public void completeExternally(String terminalStatus, Long operatorId, String comment) {
        if (terminalStatus == null || terminalStatus.trim().isEmpty()) {
            throw new IllegalArgumentException("Terminal status is required");
        }
        if (!STATUS_PENDING.equals(this.status)) {
            return;
        }

        Optional<AuditStepInstance> currentStep = resolveCurrentPendingStep();
        if (currentStep.isPresent()) {
            AuditStepInstance step = currentStep.get();
            step.setApproverId(operatorId != null ? operatorId : step.getApproverId());
            step.setComment(comment);
            step.setApprovedAt(LocalDateTime.now());
            if (STATUS_APPROVED.equals(terminalStatus)) {
                step.setStatus(STEP_STATUS_APPROVED);
            } else if (STATUS_REJECTED.equals(terminalStatus)) {
                step.setStatus(STEP_STATUS_REJECTED);
            } else {
                throw new IllegalArgumentException("Unsupported terminal status: " + terminalStatus);
            }
        }

        this.status = terminalStatus;
        this.completedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (!canRequesterWithdraw()) {
            throw new IllegalStateException("Cannot cancel: first approval step has already been handled");
        }
        AuditStepInstance current = resolveCurrentPendingStep()
                .orElseThrow(() -> new IllegalStateException("Cannot cancel: no pending step available"));

        AuditStepInstance submitStep = stepInstances.stream()
                .filter(step -> STEP_STATUS_APPROVED.equals(step.getStatus()))
                .filter(this::isRequesterSubmitStep)
                .max(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .thenComparing(step -> step.getApprovedAt() == null ? LocalDateTime.MIN : step.getApprovedAt())
                        .thenComparing(step -> step.getId() == null ? Long.MIN_VALUE : step.getId()))
                .orElse(current);

        submitStep.setStatus(STEP_STATUS_WITHDRAWN);
        submitStep.setComment("提交人撤回");
        submitStep.setApprovedAt(LocalDateTime.now());

        stepInstances.stream()
                .filter(step -> !step.equals(submitStep))
                .filter(step -> STEP_STATUS_PENDING.equals(step.getStatus()) || STEP_STATUS_WAITING.equals(step.getStatus()))
                .forEach(step -> {
                    step.setStatus(STEP_STATUS_WAITING);
                    step.setComment(null);
                    step.setApprovedAt(null);
                });

        this.status = STATUS_WITHDRAWN;
        this.completedAt = LocalDateTime.now();
    }

    public void reactivateWithdrawnStep() {
        if (!STATUS_PENDING.equals(status) && !STATUS_WITHDRAWN.equals(status) && !STATUS_REJECTED.equals(status)) {
            throw new IllegalStateException("Cannot reactivate: workflow is not in a resumable state");
        }
        AuditStepInstance withdrawnStep = stepInstances.stream()
                .filter(step -> STEP_STATUS_WITHDRAWN.equals(step.getStatus()))
                .max(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .thenComparing(step -> step.getCreatedAt() == null ? LocalDateTime.MIN : step.getCreatedAt()))
                .orElseThrow(() -> new IllegalStateException("Cannot reactivate: no withdrawn step available"));

        if (isRequesterSubmitStep(withdrawnStep)) {
            withdrawnStep.setStatus(STEP_STATUS_APPROVED);
            withdrawnStep.setComment("系统自动完成提交流程节点");
            withdrawnStep.setApprovedAt(LocalDateTime.now());

            Optional<AuditStepInstance> waitingReplayStep = stepInstances.stream()
                    .filter(step -> !step.equals(withdrawnStep))
                    .filter(step -> STEP_STATUS_WAITING.equals(step.getStatus()))
                    .sorted(Comparator.comparing(step -> step.getStepNo() == null ? Integer.MAX_VALUE : step.getStepNo()))
                    .findFirst();

            if (waitingReplayStep.isPresent()) {
                AuditStepInstance step = waitingReplayStep.get();
                step.setStatus(STEP_STATUS_PENDING);
                step.setComment(null);
                step.setApprovedAt(null);
            } else {
                appendReplayStepFromLatestRejectedStep();
            }
        } else {
            withdrawnStep.setStatus(STEP_STATUS_PENDING);
            withdrawnStep.setComment(null);
            withdrawnStep.setApprovedAt(null);
        }

        this.status = STATUS_PENDING;
        this.completedAt = null;
    }

    private boolean isRequesterSubmitStep(AuditStepInstance step) {
        if (step == null) {
            return false;
        }

        if (Integer.valueOf(1).equals(step.getStepNo())) {
            return true;
        }

        if (requesterId != null && requesterId.equals(step.getApproverId())) {
            return true;
        }

        String stepName = step.getStepName();
        return stepName != null && stepName.contains("提交");
    }

    private void appendReplayStepFromLatestRejectedStep() {
        AuditStepInstance latestRejectedStep = stepInstances.stream()
                .filter(step -> STEP_STATUS_REJECTED.equals(step.getStatus()))
                .filter(step -> !isRequesterSubmitStep(step))
                .max(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .thenComparing(step -> step.getCreatedAt() == null ? LocalDateTime.MIN : step.getCreatedAt()))
                .orElse(null);

        if (latestRejectedStep == null) {
            return;
        }

        AuditStepInstance replayStep = new AuditStepInstance();
        replayStep.setStepNo(nextStepInstanceNo());
        replayStep.setStepDefId(latestRejectedStep.getStepDefId());
        replayStep.setStepName(latestRejectedStep.getStepName());
        replayStep.setApproverId(null);
        replayStep.setApproverOrgId(latestRejectedStep.getApproverOrgId());
        replayStep.setStatus(STEP_STATUS_PENDING);
        replayStep.setComment(null);
        replayStep.setApprovedAt(null);
        addStepInstance(replayStep);
    }

    public void start(Long requesterId, Long requesterOrgId) {
        this.requesterId = requesterId;
        this.requesterOrgId = requesterOrgId;
        this.status = STATUS_PENDING;
        this.startedAt = LocalDateTime.now();
    }

    public void transfer(Long targetUserId) {
        throw new UnsupportedOperationException("Workflow task reassignment is not supported for fixed approval templates");
    }

    public void addApprover(Long approverId) {
        throw new UnsupportedOperationException("Dynamic approver insertion is not supported for fixed approval templates");
    }

    public void addStepInstance(AuditStepInstance stepInstance) {
        stepInstances.add(stepInstance);
        stepInstance.setInstance(this);
        if (stepInstance.getCreatedAt() == null) {
            stepInstance.setCreatedAt(LocalDateTime.now());
        }
    }

    public Optional<AuditStepInstance> resolveCurrentPendingStep() {
        return stepInstances.stream()
                .filter(step -> STEP_STATUS_PENDING.equals(step.getStatus()))
                .sorted(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .reversed()
                        .thenComparing(step -> step.getCreatedAt() == null ? LocalDateTime.MIN : step.getCreatedAt(),
                                Comparator.reverseOrder()))
                .findFirst();
    }

    public Optional<AuditStepInstance> resolveCurrentDisplayStep() {
        Optional<AuditStepInstance> currentPendingStep = resolveCurrentPendingStep();
        if (currentPendingStep.isPresent()) {
            return currentPendingStep;
        }

        return stepInstances.stream()
                .filter(step -> STEP_STATUS_WITHDRAWN.equals(step.getStatus()))
                .sorted(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .reversed()
                        .thenComparing(step -> step.getCreatedAt() == null ? LocalDateTime.MIN : step.getCreatedAt(),
                                Comparator.reverseOrder()))
                .findFirst();
    }

    public Optional<AuditStepInstance> resolveLatestStepInstance() {
        return stepInstances.stream()
                .sorted(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .reversed()
                        .thenComparing(step -> step.getCreatedAt() == null ? LocalDateTime.MIN : step.getCreatedAt(),
                                Comparator.reverseOrder()))
                .findFirst();
    }

    public int nextStepInstanceNo() {
        return stepInstances.stream()
                .map(AuditStepInstance::getStepNo)
                .filter(stepNo -> stepNo != null && stepNo > 0)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    public boolean hasAnyHandledStep() {
        return stepInstances.stream().anyMatch(step ->
                STEP_STATUS_APPROVED.equals(step.getStatus()) || STEP_STATUS_REJECTED.equals(step.getStatus()));
    }

    public boolean canRequesterWithdraw() {
        if (!STATUS_PENDING.equals(status)) {
            return false;
        }

        AuditStepInstance currentPendingStep = resolveCurrentPendingStep().orElse(null);
        if (currentPendingStep == null) {
            return false;
        }

        Integer currentPendingStepNo = currentPendingStep.getStepNo();
        if (currentPendingStepNo == null || currentPendingStepNo <= 1) {
            return false;
        }

        AuditStepInstance previousStep = stepInstances.stream()
                .filter(step -> step.getStepNo() != null)
                .filter(step -> step.getStepNo().equals(currentPendingStepNo - 1))
                .max(java.util.Comparator.comparing(AuditStepInstance::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .orElse(null);

        if (previousStep == null) {
            return false;
        }

        return STEP_STATUS_APPROVED.equals(previousStep.getStatus()) && isRequesterSubmitStep(previousStep);
    }

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (isDeleted == null) {
            isDeleted = false;
        }
    }
}
