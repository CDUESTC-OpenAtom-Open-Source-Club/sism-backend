package com.sism.strategy.domain.indicator;

import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.strategy.domain.indicator.IndicatorLevel;
import com.sism.strategy.domain.indicator.event.IndicatorCreatedEvent;
import com.sism.strategy.domain.indicator.event.IndicatorStatusChangedEvent;
import com.sism.shared.domain.model.base.AggregateRoot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
@Entity
@Table(name = "indicator", schema = "public")
@Access(AccessType.FIELD)
public class Indicator extends AggregateRoot<Long> {

    @Id
    @SequenceGenerator(name="Indicator_IdSeq", sequenceName="public.indicator_id_seq", allocationSize=1)
    @GeneratedValue(strategy=GenerationType.SEQUENCE, generator="Indicator_IdSeq")
    @Column(name="id")
    private Long id;

    @Column(name="task_id")
    private Long taskId;

    @Column(name="parent_indicator_id", insertable = false, updatable = false)
    private Long parentIndicatorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_indicator_id",
                foreignKey = @ForeignKey(name = "fk_indicator_parent"))
    private Indicator parentIndicator;

    @OneToMany(mappedBy = "parentIndicator",
               cascade = {CascadeType.PERSIST, CascadeType.MERGE},
               fetch = FetchType.LAZY)
    private List<Indicator> childIndicators = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_org_id", nullable = false)
    private com.sism.organization.domain.SysOrg ownerOrg;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_org_id", nullable = false)
    private com.sism.organization.domain.SysOrg targetOrg;

    @Column(name = "indicator_desc", nullable = false, columnDefinition = "TEXT")
    private String indicatorDesc;

    @Column(name="weight_percent", nullable=false)
    private BigDecimal weightPercent;

    @Column(name="sort_order", nullable=false)
    private Integer sortOrder;

    @Column(name="remark")
    private String remark;

    @Column(name="created_at", nullable=false)
    private LocalDateTime createdAt;

    @Column(name="updated_at", nullable=false)
    private LocalDateTime updatedAt;

    @Column(name="type", nullable=false)
    private String type;

    @Column(name="progress")
    private Integer progress;

    @Column(name="is_deleted", nullable=false)
    private Boolean isDeleted = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private IndicatorStatus status = IndicatorStatus.DRAFT;

    // Level is calculated dynamically from ownerOrg and targetOrg types
    // Marked as transient to avoid persisting to database
    @Transient
    private IndicatorLevel level;

    @Column(name = "responsible_user_id")
    private Long responsibleUserId;

    // 指标进度由填报审批结果直接维护；里程碑模型已移除。

    public static Indicator create(String description, com.sism.organization.domain.SysOrg ownerOrg,
                                    com.sism.organization.domain.SysOrg targetOrg,
                                    String indicatorType) {
        Indicator indicator = new Indicator();
        indicator.indicatorDesc = Objects.requireNonNull(description, "指标描述不能为空");
        indicator.ownerOrg = Objects.requireNonNull(ownerOrg, "责任组织不能为空");
        indicator.targetOrg = Objects.requireNonNull(targetOrg, "目标组织不能为空");
        indicator.weightPercent = BigDecimal.valueOf(100);
        indicator.sortOrder = 0;
        indicator.type = normalizeIndicatorType(indicatorType);
        indicator.progress = 0;
        indicator.status = IndicatorStatus.DRAFT;
        indicator.level = indicator.calculateLevel();
        indicator.createdAt = LocalDateTime.now();
        indicator.updatedAt = LocalDateTime.now();
        indicator.isDeleted = false;
        indicator.markCreated(ownerOrg.getId());
        return indicator;
    }

    public static Indicator create(String name, String description, BigDecimal weight,
                                    com.sism.organization.domain.SysOrg ownerOrg,
                                    com.sism.organization.domain.SysOrg targetOrg,
                                    String indicatorType) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("指标名称不能为空");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("指标描述不能为空");
        }
        if (weight == null) {
            throw new IllegalArgumentException("权重不能为空");
        }
        if (weight.compareTo(BigDecimal.ZERO) <= 0 || weight.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("权重必须在0到100之间");
        }
        if (ownerOrg == null) {
            throw new IllegalArgumentException("责任组织不能为空");
        }
        if (targetOrg == null) {
            throw new IllegalArgumentException("目标组织不能为空");
        }

        Indicator indicator = new Indicator();
        indicator.indicatorDesc = description;
        indicator.ownerOrg = ownerOrg;
        indicator.targetOrg = targetOrg;
        indicator.weightPercent = weight;
        indicator.sortOrder = 0;
        indicator.type = normalizeIndicatorType(indicatorType);
        indicator.progress = 0;
        indicator.status = IndicatorStatus.DRAFT;
        indicator.createdAt = LocalDateTime.now();
        indicator.updatedAt = LocalDateTime.now();
        indicator.isDeleted = false;
        indicator.markCreated(ownerOrg.getId());
        return indicator;
    }

    public String getName() {
        return this.indicatorDesc;
    }

    public void setName(String name) {
        this.indicatorDesc = name;
    }

    public String getDescription() {
        return this.indicatorDesc;
    }

    public void setDescription(String description) {
        this.indicatorDesc = description;
    }

    public void markCreated(Long ownerOrgId) {
        this.addEvent(new IndicatorCreatedEvent(this.id, this.getDescription(), ownerOrgId));
    }

    public void setType(String type) {
        this.type = normalizeIndicatorType(type);
    }

    public BigDecimal getWeight() {
        return this.weightPercent;
    }

    public void setWeight(BigDecimal weight) {
        this.weightPercent = weight;
    }

    public com.sism.organization.domain.SysOrg getOwnerOrg() {
        return this.ownerOrg;
    }

    public com.sism.organization.domain.SysOrg getTargetOrg() {
        return this.targetOrg;
    }

    public Indicator getParent() {
        return this.parentIndicator;
    }

    public void setParent(Indicator parent) {
        this.parentIndicator = parent;
        if (parent != null) {
            this.parentIndicatorId = parent.getId();
            if (!parent.getChildren().contains(this)) {
                parent.getChildren().add(this);
            }
        } else {
            this.parentIndicatorId = null;
        }
    }

    public List<Indicator> getChildren() {
        return this.childIndicators;
    }

    public String getRejectionReason() {
        // 占位方法，返回null
        return null;
    }

    public void reject(String reason) {
        if (this.status != IndicatorStatus.PENDING) {
            throw new IllegalStateException("Cannot reject indicator: not in PENDING state");
        }
        this.status = IndicatorStatus.DRAFT;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, "PENDING", "DRAFT"));
    }

    public void distribute() {
        // 下发指标：状态从DRAFT变为PENDING，等待目标组织确认
        if (this.status != IndicatorStatus.DRAFT) {
            throw new IllegalStateException("Cannot distribute indicator: not in DRAFT state");
        }
        this.status = IndicatorStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, "DRAFT", "PENDING"));
    }

    public void confirmReceive() {
        // 确认接收：状态从PENDING变为DISTRIBUTED
        if (this.status != IndicatorStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm receive: indicator not in PENDING state");
        }
        this.status = IndicatorStatus.DISTRIBUTED;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, "PENDING", "DISTRIBUTED"));
    }

    public void withdraw() {
        // 撤回指标：只能从PENDING或DISTRIBUTED状态撤回，状态变回DRAFT
        if (this.status != IndicatorStatus.PENDING && this.status != IndicatorStatus.DISTRIBUTED) {
            throw new IllegalStateException("Cannot withdraw indicator: not in PENDING or DISTRIBUTED state");
        }
        String originalStatus = this.status.toString();
        this.status = IndicatorStatus.DRAFT;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, originalStatus, "DRAFT"));
    }

    public void complete() {
        // 完成指标：保持DISTRIBUTED状态，只更新进度
        // 三状态流程中，DISTRIBUTED即为完成状态
        if (this.status != IndicatorStatus.DISTRIBUTED) {
            throw new IllegalStateException("Cannot complete indicator: not in DISTRIBUTED state");
        }
        // 状态保持DISTRIBUTED，不需要改变
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, "DISTRIBUTED", "DISTRIBUTED"));
    }

    public void submitForReview() {
        if (this.status != IndicatorStatus.DRAFT) {
            throw new IllegalStateException("Cannot submit indicator: not in DRAFT state");
        }
        this.status = IndicatorStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, "DRAFT", "PENDING"));
    }

    public void approve() {
        if (this.status != IndicatorStatus.PENDING) {
            throw new IllegalStateException("Cannot approve indicator: not in PENDING state");
        }
        this.status = IndicatorStatus.DISTRIBUTED;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, "PENDING", "DISTRIBUTED"));
    }

    public void reject() {
        if (this.status != IndicatorStatus.PENDING) {
            throw new IllegalStateException("Cannot reject indicator: not in PENDING state");
        }
        this.status = IndicatorStatus.DRAFT;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, "PENDING", "DRAFT"));
    }

    public void archive() {
        // 三状态流程中不使用ARCHIVED状态，改为逻辑删除
        if (this.isDeleted) {
            throw new IllegalStateException("Indicator already deleted");
        }
        this.isDeleted = true;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, this.status.toString(), "DELETED"));
    }

    public boolean isStrategic() {
        if (this.ownerOrg == null) {
            return false;
        }
        // Use organization type instead of hardcoded name
        var ownerOrgType = this.ownerOrg.getOrgType();
        if (ownerOrgType == null) {
            return false;
        }
        String ownerTypeName = ownerOrgType;
        // Strategic indicator: STRATEGY_DEPT -> non-COLLEGE
        boolean isFromStrategy = "STRATEGY_DEPT".equals(ownerTypeName);
        if (!isFromStrategy) {
            return false;
        }
        if (this.targetOrg == null) {
            return false;
        }
        var targetOrgType = this.targetOrg.getOrgType();
        if (targetOrgType == null) {
            return false;
        }
        String targetTypeName = targetOrgType;
        return !"COLLEGE".equals(targetTypeName);
    }

    /**
     * 计算指标层级
     * 根据ownerOrg和targetOrg的类型动态计算
     *
     * @return IndicatorLevel: FIRST(战略到职能) 或 SECOND(职能到学院)
     */
    public IndicatorLevel calculateLevel() {
        if (this.ownerOrg == null || this.targetOrg == null) {
            return IndicatorLevel.FIRST; // 默认一级指标
        }

        // 获取组织类型
        var ownerOrgType = this.ownerOrg.getOrgType();
        var targetOrgType = this.targetOrg.getOrgType();

        // 判断：如果owner是战略部，目标是非学院类型 = 一级指标
        // 如果owner是职能类型，目标 = 二级指标
        if (ownerOrgType != null) {
            String ownerTypeName = ownerOrgType;
            // 战略发展部 -> 职能处室 = 一级指标
            if ("STRATEGY_DEPT".equals(ownerTypeName)) {
                return IndicatorLevel.FIRST;
            }
            // 职能处室 -> 学院 = 二级指标
            if ("FUNCTIONAL_DEPT".equals(ownerTypeName) || "FUNCTION_DEPT".equals(ownerTypeName)) {
                return IndicatorLevel.SECOND;
            }
        }

        // 兜底：一级指标
        return IndicatorLevel.FIRST;
    }

    /**
     * 获取指标层级
     * 优先使用数据库字段，如果没有则计算
     *
     * @return IndicatorLevel: FIRST(战略到职能) 或 SECOND(职能到学院)
     */
    public IndicatorLevel getLevel() {
        if (this.level != null) {
            return this.level;
        }
        return calculateLevel();
    }

    /**
     * 设置指标层级
     */
    public void setLevel(IndicatorLevel level) {
        this.level = level;
    }

    /**
     * 判断是否是一级指标（战略到职能）
     */
    public boolean isFirstLevel() {
        return getLevel() == IndicatorLevel.FIRST;
    }

    /**
     * 判断是否是二级指标（职能到学院）
     */
    public boolean isSecondLevel() {
        return getLevel() == IndicatorLevel.SECOND;
    }

    /**
     * 判断指标是否已下发
     */
    public boolean isDistributed() {
        return this.status == IndicatorStatus.DISTRIBUTED;
    }

    /**
     * 判断指标是否待审批（下发后等待确认）
     */
    public boolean isPending() {
        return this.status == IndicatorStatus.PENDING;
    }

    /**
     * Distribute from parent indicator to target organization
     */
    public void distributeFrom(Indicator parentIndicator, com.sism.organization.domain.SysOrg targetOrg, Double weight) {
        if (this.status != IndicatorStatus.DRAFT) {
            throw new IllegalStateException("Cannot distribute: indicator must be in DRAFT state");
        }
        this.parentIndicator = parentIndicator;
        this.parentIndicatorId = parentIndicator.getId();
        this.targetOrg = targetOrg;
        this.weightPercent = BigDecimal.valueOf(weight);
        this.status = IndicatorStatus.DISTRIBUTED;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, "DRAFT", "DISTRIBUTED"));
    }

    /**
     * Check if indicator can be broken down into child indicators
     */
    public boolean canBreakdown() {
        return this.isFirstLevel() && this.status == IndicatorStatus.DISTRIBUTED;
    }

    /**
     * Mark indicator as broken down (has child indicators)
     */
    public void markAsBrokenDown() {
        // Don't change type - keep it as "定量" or "定性"
        // Just update the timestamp to track when breakdown happened
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Activate indicator
     */
    public void activate() {
        String previousStatus = this.status.toString();
        this.status = IndicatorStatus.DISTRIBUTED;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, previousStatus, "DISTRIBUTED"));
    }

    /**
     * Terminate indicator with a reason
     */
    public void terminate(String reason) {
        String previousStatus = this.status.toString();
        if (this.status == IndicatorStatus.DISTRIBUTED) {
            throw new IllegalStateException("Cannot terminate indicator: already distributed");
        }
        this.status = IndicatorStatus.DRAFT;
        this.remark = reason;
        this.updatedAt = LocalDateTime.now();
        this.addEvent(new IndicatorStatusChangedEvent(this.id, previousStatus, "TERMINATED"));
    }

    @Override
    public void validate() {
        if (indicatorDesc == null || indicatorDesc.trim().isEmpty()) {
            throw new IllegalArgumentException("指标描述不能为空");
        }
        normalizeIndicatorType(type);
        if (weightPercent == null || weightPercent.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("权重必须为正数");
        }
        if (sortOrder == null || sortOrder < 0) {
            throw new IllegalArgumentException("排序顺序不能为负数");
        }
    }

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = IndicatorStatus.DRAFT;
        }
        if (isDeleted == null) {
            isDeleted = false;
        }
    }

    private static String normalizeIndicatorType(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) {
            throw new IllegalArgumentException("指标类型不能为空");
        }

        String normalizedType = rawType.trim();
        if (!"定量".equals(normalizedType) && !"定性".equals(normalizedType)) {
            throw new IllegalArgumentException("指标类型必须是定量或定性");
        }

        return normalizedType;
    }
}
