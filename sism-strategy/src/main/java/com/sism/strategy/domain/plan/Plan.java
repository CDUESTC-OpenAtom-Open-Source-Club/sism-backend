package com.sism.strategy.domain.plan;

import com.sism.shared.domain.model.base.AggregateRoot;
import com.sism.strategy.domain.plan.event.PlanStatusChangedEvent;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "plan", schema = "public")
@Access(AccessType.FIELD)
public class Plan extends AggregateRoot<Long> {

    @Id
    @SequenceGenerator(name = "Plan_IdSeq", sequenceName = "public.plan_id_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "Plan_IdSeq")
    @Column(name = "id")
    private Long id;

    @Column(name = "cycle_id", nullable = false)
    private Long cycleId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "target_org_id", nullable = false)
    private Long targetOrgId;

    @Column(name = "created_by_org_id", nullable = false)
    private Long createdByOrgId;

    /**
     * 2026-11-08（学院指标单层口径）：该学院指标的归口职能部门（仅 FUNC_TO_COLLEGE 使用）。
     *
     * <p>为什么需要：学院指标改为「战略部 → 学院」直接下发后没有父级，
     * 而学院上报链第 3 环「分管职能部门负责人审批」必须知道该学院归口哪个职能部门。
     * 改造前这个答案藏在父级链里（父级核心指标的目标部门），单层后父级没了；
     * 组织树也推不出（8 个学院的 parent_org_id 全是 35），
     * created_by_org_id 现在固定是战略部 → 用它推席位会落错部门并派单失败。
     *
     * <p>可空：STRAT_TO_FUNC 计划不需要；存量学院计划为 null 时
     * 审批席位回退到 created_by_org_id（改造前口径），向后兼容。
     */
    @Column(name = "functional_org_id")
    private Long functionalOrgId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "plan_level", columnDefinition = "plan_level", nullable = false)
    private PlanLevel planLevel;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "audit_instance_id")
    private Long auditInstanceId;

    public static Plan create(Long cycleId, Long targetOrgId, Long createdByOrgId, PlanLevel planLevel) {
        if (cycleId == null) {
            throw new IllegalArgumentException("Cycle ID cannot be null");
        }
        if (targetOrgId == null) {
            throw new IllegalArgumentException("Target org ID cannot be null");
        }
        if (createdByOrgId == null) {
            throw new IllegalArgumentException("Created by org ID cannot be null");
        }
        if (planLevel == null) {
            throw new IllegalArgumentException("Plan level cannot be null");
        }

        Plan plan = new Plan();
        plan.cycleId = cycleId;
        plan.targetOrgId = targetOrgId;
        plan.createdByOrgId = createdByOrgId;
        plan.planLevel = planLevel;
        plan.createdAt = LocalDateTime.now();
        plan.updatedAt = LocalDateTime.now();
        plan.isDeleted = false;
        plan.status = PlanStatus.DRAFT.value();
        return plan;
    }

    public void activate() {
        if (PlanStatus.DISTRIBUTED.value().equals(this.status)) {
            throw new IllegalStateException("Plan is already distributed");
        }
        String previousStatus = this.status;
        this.status = PlanStatus.DISTRIBUTED.value();
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new PlanStatusChangedEvent(this.id, previousStatus, this.status));
    }

    /**
     * 指标下发后同步计划容器状态（幂等）：仅当计划仍为 DRAFT 时推进为 DISTRIBUTED。
     * PENDING（审批中）/ RETURNED 不在此处理，仍走各自的工作流路径；
     * 已 DISTRIBUTED 时静默返回，避免与 activate()/approve() 的防重冲突。
     */
    public boolean promoteFromDraftOnIndicatorDistributed() {
        if (!PlanStatus.DRAFT.value().equals(this.status)) {
            return false;
        }
        String previousStatus = this.status;
        this.status = PlanStatus.DISTRIBUTED.value();
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new PlanStatusChangedEvent(this.id, previousStatus, this.status));
        return true;
    }

    public void ensureCanSubmitForApproval() {
        if (!PlanStatus.DRAFT.value().equals(this.status) && !PlanStatus.RETURNED.value().equals(this.status)) {
            throw new IllegalStateException("Plan must be in DRAFT or RETURNED state to submit for approval");
        }
    }

    public void ensureCanSubmitForApproval(boolean allowDistributed) {
        if (allowDistributed && PlanStatus.DISTRIBUTED.value().equals(this.status)) {
            return;
        }
        ensureCanSubmitForApproval();
    }

    public void submitForApproval() {
        submitForApproval(false);
    }

    public void submitForApproval(boolean allowDistributed) {
        String previousStatus = this.status;
        ensureCanSubmitForApproval(allowDistributed);
        this.status = PlanStatus.PENDING.value();
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new PlanStatusChangedEvent(this.id, previousStatus, this.status));
    }

    public void approve() {
        if (PlanStatus.DISTRIBUTED.value().equals(this.status)) {
            throw new IllegalStateException("Plan is already distributed");
        }
        String previousStatus = this.status;
        this.status = PlanStatus.DISTRIBUTED.value();
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new PlanStatusChangedEvent(this.id, previousStatus, this.status));
    }

    public void returnForRevision() {
        String previousStatus = this.status;
        this.status = PlanStatus.RETURNED.value();
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new PlanStatusChangedEvent(this.id, previousStatus, this.status));
    }

    public void withdraw() {
        String previousStatus = this.status;
        this.status = PlanStatus.DRAFT.value();
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new PlanStatusChangedEvent(this.id, previousStatus, this.status));
    }

    public void archive() {
        if (Boolean.TRUE.equals(this.isDeleted)) {
            throw new IllegalStateException("Plan is already archived");
        }
        String previousStatus = this.status;
        this.isDeleted = true;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new PlanStatusChangedEvent(this.id, previousStatus, "ARCHIVED"));
    }

    public boolean isEditable() {
        return PlanStatus.DRAFT.value().equals(this.status) || PlanStatus.RETURNED.value().equals(this.status);
    }

    public boolean isDistributed() {
        return PlanStatus.DISTRIBUTED.value().equals(this.status);
    }

    public boolean isReturned() {
        return PlanStatus.RETURNED.value().equals(this.status);
    }

    @Override
    public void validate() {
        if (cycleId == null) {
            throw new IllegalArgumentException("Cycle ID is required");
        }
        if (targetOrgId == null) {
            throw new IllegalArgumentException("Target org ID is required");
        }
        if (createdByOrgId == null) {
            throw new IllegalArgumentException("Created by org ID is required");
        }
        if (planLevel == null) {
            throw new IllegalArgumentException("Plan level is required");
        }
    }
}
