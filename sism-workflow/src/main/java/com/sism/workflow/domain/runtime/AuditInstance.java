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
    /**
     * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：填报人「取消异动」主动终结的终态。
     * 与 APPROVED 同为链条仅有的两种终态，均归入「审批历史」；
     * 驳回退回待重报（WITHDRAWN 且 completedAt 为空）是活链，不进历史。
     */
    public static final String STATUS_CANCELLED = "CANCELLED";
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

    /**
     * 异动批次号（audit_instance.batch_id 列早已存在，V94 迁移引入，由
     * sism-strategy 发起异动时直写；2026-10-30 起映射到实体供读模型使用）。
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案——
     * 同一次「发起异动」的多个审批实例共享同一批次号，消息中心按该批次号
     * 聚合成 1 条待办。此处仅映射既有列做读取，不改变「每实体一个实例」的模型。
     */
    @Column(name = "batch_id")
    private String batchId;

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
     * 审批通过并留痕审核进度（P1 上报链改造）。
     *
     * @param appraisalLevel 本节点审核进度 AHEAD/NORMAL/DELAYED，可为空（非鉴定场景）
     */
    public void approve(Long userId, String comment, String appraisalLevel) {
        approve(userId, comment, appraisalLevel, false);
    }

    /**
     * 2026-10-20：系统管理员代批通道（导入自动下发使用）。
     * systemOverride=true 时跳过"必须是当前节点被指派审批人"的校验，
     * 但仍要求流程处于审批中，且审批意见里会记录代批人，留痕不丢。
     */
    public void approve(Long userId, String comment, String appraisalLevel, boolean systemOverride) {
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

        // 2026-10-20 安全修复（ISSUE-001 越权审批）：step.approverId 是被指派的审批人，
        // 只有当前节点审批人本人可审批。此前直接覆盖 approverId，任何登录用户都能推进流程。
        // systemOverride=true 用于系统管理员代批（导入自动下发），属受控例外。
        if (!systemOverride) {
            assertCurrentApprover(current, userId, "approve");
        }

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
        // 2026-10-20 安全修复（同 ISSUE-001）：仅当前节点审批人可驳回
        assertCurrentApprover(current, userId, "reject");
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

    /**
     * 2026-10-30 用户定案（异动撤回=退回填报人活链）：异动发起人在审批中的「撤回」。
     * 与 {@link #cancel()}（撤回即终结，completedAt=now 归历史）不同：把链退回
     * 填报人节点、链保持存活——实例转 WITHDRAWN 且 completedAt 保持 NULL。
     * 2026-10-30 二次定案（撤回节点生成与 cancel 同构：原地翻转、零新增行）：
     * ① 当前在审审批步骤原地置 WITHDRAWN（备注「提交人撤回」）；
     * ② 本实例最新一条提交类步骤（{@link #isRequesterSubmitStep}，即链位所在
     *    的填报人节点）同样原地置 WITHDRAWN（备注「提交人撤回」），代表链位
     *    回到填报人——不再由用例层追加「退回提交步骤」行（旧行为会让节点链
     *    越撤越长）。之后填报人可重新发起（resume 原地复活同链）或「取消异动」
     *    终结归历史。
     */
    public void withdrawToRequester() {
        if (!STATUS_PENDING.equals(status)) {
            throw new IllegalStateException("Cannot withdraw: workflow is not in review");
        }
        AuditStepInstance current = resolveCurrentPendingStep()
                .orElseThrow(() -> new IllegalStateException("Cannot withdraw: no pending step available"));

        current.setStatus(STEP_STATUS_WITHDRAWN);
        current.setComment("提交人撤回");
        current.setApprovedAt(LocalDateTime.now());

        flipLatestSubmitStepWithdrawnInPlace();

        this.status = STATUS_WITHDRAWN;
        this.completedAt = null;
    }

    /**
     * 2026-10-30 二次定案（异动撤回零新增行）：把最新一条「已通过」的提交类步骤
     * 原地翻转为 WITHDRAWN「提交人撤回」（选取口径与 {@link #cancel()} 一致：
     * stepNo → approvedAt → id 取最大）。撤回前该步骤已 APPROVED 留痕，翻转后
     * 与「上报撤回链形」同构：链位（最新 WITHDRAWN 提交类步骤）回到填报人。
     * 找不到提交类步骤（极端历史数据）时保持旧行为——仅翻转在审审批步骤。
     */
    private void flipLatestSubmitStepWithdrawnInPlace() {
        stepInstances.stream()
                .filter(step -> STEP_STATUS_APPROVED.equals(step.getStatus()))
                .filter(this::isRequesterSubmitStep)
                .max(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .thenComparing(step -> step.getApprovedAt() == null ? LocalDateTime.MIN : step.getApprovedAt())
                        .thenComparing(step -> step.getId() == null ? Long.MIN_VALUE : step.getId()))
                .ifPresent(submitStep -> {
                    submitStep.setStatus(STEP_STATUS_WITHDRAWN);
                    submitStep.setComment("提交人撤回");
                    submitStep.setApprovedAt(LocalDateTime.now());
                });
    }

    /**
     * 2026-10-07：撤回前抓取当前待审批步骤的审批人名单，
     * 供「审批链已撤回」实时通知使用（人被置回 WAITING 后就查不到了）。
     */
    public List<Long> getPendingApproverIds() {
        return stepInstances.stream()
                .filter(step -> STEP_STATUS_PENDING.equals(step.getStatus()))
                .map(AuditStepInstance::getApproverId)
                .filter(approverId -> approverId != null)
                .toList();
    }

    /**
     * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：填报人对「退回待重报」
     * 异动链的主动终结（取消异动）。与 {@link #cancel()}（审批中撤回）不同：
     * 仅当链条处于驳回退回填报人状态时允许——状态 WITHDRAWN、completedAt 为空
     * （未完结）、且存在退回的提交步骤；取消后整条链进入终态 CANCELLED 并归入
     * 审批历史。退回的提交步骤保持原样留痕，不额外改写步骤状态。
     * 2026-10-30 二次定案（异动撤回零新增行）：退回提交步骤按
     * {@link #isRequesterSubmitStep} 双形态识别——新形态（发起人撤回把提交类
     * 步骤原地置 WITHDRAWN）与旧形态（存量数据里追加的退回提交行）都满足
     * 「存在 WITHDRAWN 提交类步骤」，取消口径不变。
     */
    public void cancelReturned() {
        if (!STATUS_WITHDRAWN.equals(status) || completedAt != null) {
            throw new IllegalStateException("Cannot cancel: workflow is not returned for resubmission");
        }
        boolean hasReturnedSubmitStep = stepInstances.stream()
                .filter(step -> STEP_STATUS_WITHDRAWN.equals(step.getStatus()))
                .anyMatch(this::isRequesterSubmitStep);
        if (!hasReturnedSubmitStep) {
            throw new IllegalStateException("Cannot cancel: no returned submit step available");
        }
        this.status = STATUS_CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    public void reactivateWithdrawnStep() {
        reactivateWithdrawnStep(null);
    }

    /**
     * 2026-10-31 修复（重报说明被丢弃，图05）：重报提交时前端收集的「重报说明」
     * 经提交链路透传进来，写入被复活的提交步备注。comment 为空（null/空白）时
     * 才退回既有兜底文案「提交人重新提交（未填写说明）」，缺省行为与改前一致。
     */
    public void reactivateWithdrawnStep(String submitComment) {
        if (!STATUS_PENDING.equals(status) && !STATUS_WITHDRAWN.equals(status) && !STATUS_REJECTED.equals(status)) {
            throw new IllegalStateException("Cannot reactivate: workflow is not in a resumable state");
        }
        // 2026-10-30 二次定案（异动撤回零新增行）：双形态识别「退回提交步骤」——
        // 新形态：发起人撤回把提交类步骤原地置 WITHDRAWN（审批环节另有一条
        // WITHDRAWN，stepNo 更大，不能按「最新 WITHDRAWN」选，否则会误走
        // 审批环节直复活分支、提交步留在 WITHDRAWN 造成链形不一致）；
        // 旧形态（存量数据）：用例层追加的退回提交行（stepNo 最大、提交类）。
        // 两种形态都优先取「最新 WITHDRAWN 的提交类步骤」来翻转；都没有时
        // 回落既有「最新 WITHDRAWN 步骤」口径（cancel/reject 链形不受影响）。
        AuditStepInstance withdrawnStep = resolveLatestWithdrawnSubmitStep()
                .or(() -> stepInstances.stream()
                        .filter(step -> STEP_STATUS_WITHDRAWN.equals(step.getStatus()))
                        .max(Comparator
                                .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                                .thenComparing(step -> step.getCreatedAt() == null ? LocalDateTime.MIN : step.getCreatedAt())))
                .orElseThrow(() -> new IllegalStateException("Cannot reactivate: no withdrawn step available"));

        if (isRequesterSubmitStep(withdrawnStep)) {
            withdrawnStep.setStatus(STEP_STATUS_APPROVED);
            // 2026-10-20 修复（复盘图14）：同上，避免"系统自动"误导。
            // 2026-10-31：重报说明非空时用提交人填写的说明，兜底文案仅在缺省时保留。
            withdrawnStep.setComment(submitComment == null || submitComment.isBlank()
                    ? "提交人重新提交（未填写说明）"
                    : submitComment.trim());
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
                // 2026-10-30（异动撤回=退回填报人活链）：发起人撤回的链形里在审环节
                // 被置为 WITHDRAWN——既无 WAITING 兄弟可复活、也无 REJECTED 可重放，
                // 重新提交后把被撤回的审批环节复活为 PENDING，审批人继续审。
                // 旧 cancel 链形（有 WAITING）与 reject 链形（有 REJECTED）不会走到这里。
                if (resolveCurrentPendingStep().isEmpty()) {
                    reviveLatestWithdrawnApprovalStep();
                }
            }
        } else {
            withdrawnStep.setStatus(STEP_STATUS_PENDING);
            withdrawnStep.setComment(null);
            withdrawnStep.setApprovedAt(null);
        }

        this.status = STATUS_PENDING;
        this.completedAt = null;
    }

    /**
     * 提交类步骤识别（三口径，兼容存量）：stepNo=1（流程定义首步必为提交）、
     * 审批人=发起人（撤回/重报后链位步的留痕）、步骤名含「提交」（未指派的历史行）。
     * 2026-10-30 二次定案：异动撤回零新增行后，「退回提交步骤」的新形态（提交步
     * 原地 WITHDRAWN）与旧形态（追加退回提交行）都由本口径识别，
     * WorkflowBusinessStatusSyncService 与 listReturnedMutationChains 的 SQL
     * 识别同口径（step_no=1 OR approver=requester OR step_name LIKE '%提交%'）。
     */
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

    /**
     * 2026-10-30 二次定案（异动撤回零新增行）：最新的「WITHDRAWN 提交类步骤」——
     * 「退回提交步骤」的双形态识别（与 {@link #isRequesterSubmitStep} 同口径）：
     * 新形态 = 发起人撤回把提交类步骤原地置 WITHDRAWN；旧形态（存量数据）=
     * 用例层追加的退回提交行。两条链形的「链位」都由该步骤代表。
     */
    private Optional<AuditStepInstance> resolveLatestWithdrawnSubmitStep() {
        return stepInstances.stream()
                .filter(step -> STEP_STATUS_WITHDRAWN.equals(step.getStatus()))
                .filter(this::isRequesterSubmitStep)
                .max(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .thenComparing(step -> step.getCreatedAt() == null ? LocalDateTime.MIN : step.getCreatedAt()));
    }

    /**
     * 2026-10-30（异动撤回=退回填报人活链）：把被撤回的在审审批环节（WITHDRAWN
     * 且非提交步骤）复活为 PENDING。仅在重新提交后既无 WAITING 兄弟、也无
     * REJECTED 可重放时调用（发起人撤回链形专用品），对既有链形零影响。
     * 2026-10-30 二次定案：新形态（提交步被原地置 WITHDRAWN）与旧形态
     * （追加的退回提交行）重报都经这里原地复活审批环节，零新增行。
     */
    private void reviveLatestWithdrawnApprovalStep() {
        stepInstances.stream()
                .filter(step -> STEP_STATUS_WITHDRAWN.equals(step.getStatus()))
                .filter(step -> !isRequesterSubmitStep(step))
                .max(Comparator
                        .comparing((AuditStepInstance step) -> step.getStepNo() == null ? Integer.MIN_VALUE : step.getStepNo())
                        .thenComparing(step -> step.getCreatedAt() == null ? LocalDateTime.MIN : step.getCreatedAt()))
                .ifPresent(step -> {
                    step.setStatus(STEP_STATUS_PENDING);
                    step.setComment(null);
                    step.setApprovedAt(null);
                });
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

    /**
     * 2026-10-20 安全修复：校验操作人是否为当前待办节点的被指派审批人。
     * approverId 为 null 视为未指派（历史数据），放行以兼容存量实例。
     */
    private void assertCurrentApprover(AuditStepInstance current, Long userId, String action) {
        Long assigned = current.getApproverId();
        if (assigned == null) {
            return;
        }
        if (userId == null || !assigned.equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "当前用户不是该节点的审批人，无权"
                            + ("approve".equals(action) ? "审批" : "驳回")
                            + "（当前审批人ID=" + assigned + "）");
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
