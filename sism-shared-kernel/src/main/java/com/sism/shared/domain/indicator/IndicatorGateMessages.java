package com.sism.shared.domain.indicator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 指标门禁拦截文案的公共拼装（2026-10-30 用户定案口径）。
 *
 * 上报链两类门禁共用同一套「指标点名」拼装，保证口径一致、不各说各话：
 * ① 审批侧兜底门（sism-workflow）：审核进度未鉴定 → 「未完成审核：还有 N 项指标的审核进度未鉴定（…）…」；
 * ② 提交侧门禁（sism-execution）：自评进度未填写 → 「自评未完成：还有 N 项指标的自评进度未填写（…）…」。
 *
 * 三条硬规则（2026-10-30 用户定案）：
 * a) 只报指标名称，绝不回退报指标 ID（老板看不懂数字行列）；
 * b) 名称超 20 字截断补省略号、缺失兜底「未知指标」，避免文案被撑爆或留空白引号；
 * c) 超过 3 项只点名前 3 个，收尾「等 N 项」。
 */
public final class IndicatorGateMessages {

    /** 拦截文案里最多点名的指标数，超出部分用「等 N 项」收尾。 */
    public static final int NAME_PREVIEW_LIMIT = 3;

    /** 指标名称超长截断长度（超出补省略号），避免文案被长名称撑爆。 */
    public static final int INDICATOR_NAME_MAX_LENGTH = 20;

    private IndicatorGateMessages() {
    }

    /**
     * 拼装门禁拦截文案：
     * 「{leadIn}：还有 {N} 项指标的{subject}（『指标名A』『指标名B』 等 N 项）。{guidance}」
     *
     * @param leadIn         开头定性词（如「未完成审核」「自评未完成」）
     * @param subject        缺口对象短语（如「审核进度未鉴定」「自评进度未填写」）
     * @param indicatorIds   未达标的指标 ID 列表（决定 N 与点名顺序）
     * @param indicatorNames 指标 ID → 指标名称（缺失时兜底「未知指标」，不回退报 ID）
     * @param guidance       指引动作的完整尾句（含句号）
     */
    public static String buildNamedGateMessage(String leadIn,
                                               String subject,
                                               List<Long> indicatorIds,
                                               Map<Long, String> indicatorNames,
                                               String guidance) {
        int total = indicatorIds == null ? 0 : indicatorIds.size();
        Map<Long, String> names = indicatorNames == null ? Map.of() : indicatorNames;
        String preview = (indicatorIds == null ? List.<Long>of() : indicatorIds).stream()
                .limit(NAME_PREVIEW_LIMIT)
                .map(id -> "『" + truncateIndicatorName(names.get(id)) + "』")
                .collect(Collectors.joining());
        if (total > NAME_PREVIEW_LIMIT) {
            preview += " 等 " + total + " 项";
        }
        return leadIn + "：还有 " + total + " 项指标的" + subject + "（" + preview + "）。" + guidance;
    }

    /** 指标名称超长截断（超出补省略号）；名称缺失兜底「未知指标」，绝不在文案里留空引号。 */
    public static String truncateIndicatorName(String name) {
        if (name == null || name.isBlank()) {
            return "未知指标";
        }
        String trimmed = name.trim();
        return trimmed.length() <= INDICATOR_NAME_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, INDICATOR_NAME_MAX_LENGTH) + "…";
    }
}
