package com.sism.iam.domain.notification;

import com.sism.shared.domain.model.base.AggregateRoot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Notification - 通知实体
 * 映射到: alert_event 表
 */
@Getter
@Setter
@Entity
@Table(name = "alert_event")
@Access(AccessType.FIELD)
public class Notification extends AggregateRoot<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "indicator_id", nullable = false)
    private Long indicatorId;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "window_id", nullable = false)
    private Long windowId;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "actual_percent", nullable = false)
    private BigDecimal actualPercent;

    @Column(name = "expected_percent", nullable = false)
    private BigDecimal expectedPercent;

    @Column(name = "gap_percent", nullable = false)
    private BigDecimal gapPercent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    private String detailJson;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_note", columnDefinition = "TEXT")
    private String handledNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public void validate() {
        // 告警事件的基本验证
        if (indicatorId == null) {
            throw new IllegalArgumentException("Indicator ID is required");
        }
        if (ruleId == null) {
            throw new IllegalArgumentException("Rule ID is required");
        }
        if (windowId == null) {
            throw new IllegalArgumentException("Window ID is required");
        }
        if (severity == null || severity.isBlank()) {
            throw new IllegalArgumentException("Severity is required");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Status is required");
        }
    }
}
