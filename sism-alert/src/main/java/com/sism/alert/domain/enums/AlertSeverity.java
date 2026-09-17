package com.sism.alert.domain.enums;

import java.util.Locale;

/**
 * Alert severity enumeration
 * Defines the severity levels of alert events
 *
 * P1 上报链改造（2026-09-17）：扩容 AHEAD / NORMAL 两档，承载「进度等级」三档语义
 * （超前/正常/延期）。原 INFO/WARNING/CRITICAL 保留，兼容历史数据与既有预警规则；
 * 数据库约束已由 V88 同步扩容。
 */
public enum AlertSeverity {
    /**
     * Informational alert - gap <= 10%
     */
    INFO,

    /**
     * Warning alert - gap 10-20%
     */
    WARNING,

    /**
     * Critical alert - gap > 20%
     */
    CRITICAL,

    /**
     * 进度等级：超前完成（人工鉴定）
     */
    AHEAD,

    /**
     * 进度等级：正常（人工鉴定）
     */
    NORMAL;

    /**
     * Normalizes legacy alert severity labels to the canonical database vocabulary.
     *
     * @param severity severity label from API or persistence
     * @return canonical severity label or {@code null} when unsupported
     */
    public static AlertSeverity normalize(String severity) {
        if (severity == null || severity.trim().isEmpty()) {
            return null;
        }

        return switch (severity.trim().toUpperCase(Locale.ROOT)) {
            case "AHEAD", "超前", "超前完成" -> AHEAD;
            case "NORMAL", "OK", "正常" -> NORMAL;
            case "DELAYED", "延期", "延后" -> WARNING;
            case "MAJOR", "WARNING" -> WARNING;
            case "MINOR", "INFO" -> INFO;
            case "CRITICAL" -> CRITICAL;
            default -> null;
        };
    }
}
