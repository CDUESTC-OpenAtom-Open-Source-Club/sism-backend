package com.sism.analytics.domain;

import com.sism.shared.domain.model.base.AggregateRoot;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * DataExport - 数据导出聚合根
 * 代表一次数据导出任务
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_export")
@Access(AccessType.FIELD)
public class DataExport extends AggregateRoot<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String FORMAT_EXCEL = ExportFormat.EXCEL.getCode();
    public static final String FORMAT_CSV = ExportFormat.CSV.getCode();
    public static final String FORMAT_PDF = ExportFormat.PDF.getCode();

    @Column(name = "task_name", nullable = false, length = 255)
    private String name;

    @Column(name = "task_type", nullable = false, length = 50)
    private String type;

    @Column(name = "export_format", nullable = false, length = 50)
    private String format;

    @Column(name = "status", nullable = false, length = 50)
    private String status = STATUS_PENDING;

    @Column(name = "result_file_path", length = 1000)
    private String filePath;

    @Column(name = "result_file_size")
    private Long fileSize;

    @Column(name = "creator_id", nullable = false)
    private Long requestedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "start_time")
    private LocalDateTime startedAt;

    @Column(name = "end_time")
    private LocalDateTime completedAt;

    @Column(name = "error_log", length = 1000)
    private String errorMessage;

    @Column(name = "task_params", columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 创建数据导出任务
     */
    public static DataExport create(String name, String type, String format, Long requestedBy, String parameters) {
        DataExport export = new DataExport();
        if (name == null) {
            throw new IllegalArgumentException("Export name cannot be null");
        }
        export.name = name;
        if (type == null) {
            throw new IllegalArgumentException("Export type cannot be null");
        }
        export.type = type;
        if (format == null) {
            throw new IllegalArgumentException("Export format cannot be null");
        }
        export.format = format;
        if (requestedBy == null) {
            throw new IllegalArgumentException("Requested by cannot be null");
        }
        export.requestedBy = requestedBy;
        export.parameters = parameters;
        export.requestedAt = LocalDateTime.now();
        export.validate();
        return export;
    }

    /**
     * 验证导出参数
     */
    public void validate() {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Export name cannot be blank");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("Export name cannot exceed 255 characters");
        }
        if (!isValidFormat(format)) {
            throw new IllegalArgumentException("Invalid export format: " + format);
        }
        if (requestedBy == null || requestedBy <= 0) {
            throw new IllegalArgumentException("Requested by must be a positive number");
        }
    }

    /**
     * 开始导出处理
     */
    public void startProcessing() {
        if (!STATUS_PENDING.equals(status)) {
            throw new IllegalStateException("Export must be in PENDING status to start processing");
        }

        this.status = STATUS_PROCESSING;
        this.startedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 完成导出
     */
    public void complete(String filePath, Long fileSize) {
        if (!STATUS_PROCESSING.equals(status)) {
            throw new IllegalStateException("Export must be in PROCESSING status to complete");
        }

        this.status = STATUS_COMPLETED;
        this.filePath = Objects.requireNonNull(filePath, "File path cannot be null");
        this.fileSize = Objects.requireNonNull(fileSize, "File size cannot be null");
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 导出失败
     */
    public void fail(String errorMessage) {
        if (!STATUS_PROCESSING.equals(status) && !STATUS_PENDING.equals(status)) {
            throw new IllegalStateException("Export must be in PENDING or PROCESSING status to fail");
        }

        this.status = STATUS_FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 重试失败的导出
     */
    public void retry() {
        if (!STATUS_FAILED.equals(status)) {
            throw new IllegalStateException("Export must be in FAILED status to retry");
        }

        this.status = STATUS_PENDING;
        this.errorMessage = null;
        this.startedAt = null;
        this.completedAt = null;
        this.filePath = null;
        this.fileSize = null;
        this.requestedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 删除导出任务
     */
    public void delete() {
        this.deleted = true;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 验证导出格式
     */
    private boolean isValidFormat(String format) {
        return ExportFormat.isValidCode(format);
    }

    @Override
    public LocalDateTime getCreatedAt() {
        return requestedAt;
    }

    @Override
    public void setCreatedAt(LocalDateTime createdAt) {
        this.requestedAt = createdAt;
    }

    /**
     * 导出是否可下载
     */
    public boolean isDownloadable() {
        return STATUS_COMPLETED.equals(status) && !deleted;
    }

    /**
     * 导出是否可重试
     */
    public boolean isRetryable() {
        return STATUS_FAILED.equals(status) && !deleted;
    }

    /**
     * 获取导出处理时间（秒）
     */
    public Long getProcessingTimeInSeconds() {
        if (startedAt == null || completedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, completedAt).getSeconds();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataExport that = (DataExport) o;
        return deleted == that.deleted &&
                Objects.equals(getId(), that.getId()) &&
                Objects.equals(name, that.name) &&
                Objects.equals(type, that.type) &&
                Objects.equals(format, that.format) &&
                Objects.equals(status, that.status) &&
                Objects.equals(requestedBy, that.requestedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), name, type, format, status, requestedBy, deleted);
    }
}
