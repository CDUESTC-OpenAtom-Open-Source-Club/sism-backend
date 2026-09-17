package com.sism.enums;

import java.util.Locale;

/**
 * 进度等级（P1 上报链改造引入的三档人工判定档位）。
 *
 * <p>口径依据《SISM 会议对齐 2026-09-17》：填报人自评 + 上级鉴定共用同一套三档，
 * 纯人工判定，不做自动计算。术语上前端展示为「进度等级」（原「预警等级」），
 * 后端字段命名不变。</p>
 *
 * <p>与旧预警档位的归一关系（normalize）：
 * 旧滞后档（INFO/MINOR/WARNING/MAJOR/CRITICAL）统一归并为 {@link #DELAYED}；
 * OK 归并为 {@link #NORMAL}；新增 AHEAD 表达「超前完成」。</p>
 */
public enum ProgressLevel {

    /** 超前完成（提前完成） */
    AHEAD,

    /** 正常推进 */
    NORMAL,

    /** 延期（滞后） */
    DELAYED;

    /**
     * 归一化输入为规范进度等级。
     * 接受规范码、中文名与旧预警档位码；无法识别时返回 {@code null}。
     */
    public static ProgressLevel normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "AHEAD", "超前", "超前完成" -> AHEAD;
            case "NORMAL", "OK", "正常" -> NORMAL;
            case "DELAYED",
                 // 旧预警档位统一归并为延期
                 "INFO", "MINOR", "WARN", "WARNING", "MAJOR", "CRITICAL",
                 "延期", "延后", "滞后" -> DELAYED;
            default -> null;
        };
    }
}
