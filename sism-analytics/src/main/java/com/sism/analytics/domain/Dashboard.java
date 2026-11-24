package com.sism.analytics.domain;

import com.sism.shared.domain.model.base.AggregateRoot;
import com.sism.shared.domain.model.base.DomainEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Dashboard - 仪表板聚合根
 * 代表用户可定制的数据可视化仪表板
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "analytics_dashboard")
@Access(AccessType.FIELD)
public class Dashboard extends AggregateRoot<Long> {

    private static final int MAX_CONFIG_LENGTH = 10_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dashboard_name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(name = "config", columnDefinition = "TEXT")
    private String config;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 创建仪表板
     */
    public static Dashboard create(String name, String description, Long userId, boolean isPublic, String config) {
        Dashboard dashboard = new Dashboard();
        if (name == null) {
            throw new IllegalArgumentException("Dashboard name cannot be null");
        }
        dashboard.name = name;
        dashboard.description = description;
        if (userId == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }
        dashboard.userId = userId;
        dashboard.isPublic = isPublic;
        dashboard.config = config;
        dashboard.createdAt = LocalDateTime.now();

        dashboard.validate();
        return dashboard;
    }

    /**
     * 验证仪表板参数
     */
    public void validate() {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Dashboard name cannot be blank");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("Dashboard name cannot exceed 255 characters");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID must be a positive number");
        }
        if (description != null && description.length() > 1000) {
            throw new IllegalArgumentException("Description cannot exceed 1000 characters");
        }
        if (description != null && description.isBlank()) {
            throw new IllegalArgumentException("Description cannot be blank");
        }
        if (config != null && config.length() > MAX_CONFIG_LENGTH) {
            throw new IllegalArgumentException("Config cannot exceed " + MAX_CONFIG_LENGTH + " characters");
        }
        if (config != null && config.isBlank()) {
            throw new IllegalArgumentException("Config cannot be blank");
        }
    }

    /**
     * 更新仪表板信息
     */
    public void update(String name, String description, boolean isPublic, String config) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        this.isPublic = isPublic;
        if (config != null) {
            this.config = config;
        }
        this.updatedAt = LocalDateTime.now();

        validate();
    }

    /**
     * 更新配置
     */
    public void updateConfig(String config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        if (this.config.isBlank()) {
            throw new IllegalArgumentException("Config cannot be blank");
        }
        if (this.config.length() > MAX_CONFIG_LENGTH) {
            throw new IllegalArgumentException("Config cannot exceed " + MAX_CONFIG_LENGTH + " characters");
        }
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 设置为公开
     */
    public void makePublic() {
        this.isPublic = true;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 设置为私有
     */
    public void makePrivate() {
        this.isPublic = false;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 删除仪表板
     */
    public void delete() {
        this.deleted = true;
        this.updatedAt = LocalDateTime.now();
        addEvent(new DashboardDeletedEvent(getId(), userId, updatedAt));
    }

    /**
     * 复制仪表板到新用户
     */
    public Dashboard copyToUser(Long targetUserId) {
        Objects.requireNonNull(targetUserId, "Target user ID cannot be null");
        if (targetUserId <= 0) {
            throw new IllegalArgumentException("Target user ID must be a positive number");
        }

        Dashboard copied = new Dashboard();
        copied.name = this.name + " (副本)";
        copied.description = this.description;
        copied.userId = targetUserId;
        copied.isPublic = false;
        copied.config = this.config;
        copied.createdAt = LocalDateTime.now();

        return copied;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Dashboard dashboard = (Dashboard) o;
        return isPublic == dashboard.isPublic &&
                deleted == dashboard.deleted &&
                Objects.equals(getId(), dashboard.getId()) &&
                Objects.equals(name, dashboard.name) &&
                Objects.equals(userId, dashboard.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), name, userId, isPublic, deleted);
    }
}

class DashboardDeletedEvent implements DomainEvent {

    private final Long dashboardId;
    private final Long userId;
    private final LocalDateTime occurredAt;

    DashboardDeletedEvent(Long dashboardId, Long userId, LocalDateTime occurredAt) {
        this.dashboardId = dashboardId;
        this.userId = userId;
        this.occurredAt = occurredAt;
    }

    public Long getDashboardId() {
        return dashboardId;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String getEventType() {
        return "DashboardDeleted";
    }
}
