package com.sism.strategy.domain.plan;

/**
 * 计划显示名拼装（2026-10-31 用户定案）：消灭 "Plan N" 占位拼接名，统一为
 * 「{年份}年度 计划（{源部门名} -> {目标部门名}）」人话口径。
 *
 * <p>输出格式与前端 frontend/src/3-features/approval/lib/approval-utils.ts 的
 * buildPlanReferenceLabel 逐字符镜像：年份与「计划」之间一个半角空格、
 * ASCII 箭头 " -> " 两侧各一个半角空格、全角括号。降级规则也一致：
 * 单侧部门名缺 → 只写另一侧「（部门名）」；双侧缺 → 只写计划本体；
 * 年份缺 → 不写年度段；年份与部门名全缺（标签恰为「计划」）→ 回退 fallback。
 * 任何分支不得输出 "Plan N" 式占位名或裸数字 ID。
 */
public final class PlanDisplayName {

    private static final String BARE_LABEL = "计划";

    private PlanDisplayName() {
    }

    /** 完整口径：年份 + 源/目标部门名（无兜底名）。 */
    public static String planReferenceLabel(Integer year, String sourceOrgName, String targetOrgName) {
        return planReferenceLabel(year, sourceOrgName, targetOrgName, null);
    }

    /**
     * 带兜底名版本：仅当年份与部门名全缺（标签恰为「计划」）时才返回 fallback，
     * 与前端 buildPlanReferenceLabel 的 fallback 语义一致。
     */
    public static String planReferenceLabel(Integer year, String sourceOrgName, String targetOrgName, String fallback) {
        String source = normalize(sourceOrgName);
        String target = normalize(targetOrgName);
        String route;
        if (!source.isEmpty() && !target.isEmpty()) {
            route = "（" + source + " -> " + target + "）";
        } else if (!source.isEmpty()) {
            route = "（" + source + "）";
        } else if (!target.isEmpty()) {
            route = "（" + target + "）";
        } else {
            route = "";
        }
        boolean hasYear = year != null && year > 0;
        String label = (hasYear ? year + "年度 " : "") + BARE_LABEL + route;
        if (BARE_LABEL.equals(label)) {
            String fallbackName = normalize(fallback);
            if (!fallbackName.isEmpty()) {
                return fallbackName;
            }
        }
        return label;
    }

    /**
     * 前端 Number() 语义镜像：纯数字串 → 年份数值，
     * null / 空白 / 非数字 / 溢出 → null（视为无年份）。
     */
    public static Integer toYear(String rawYear) {
        if (rawYear == null) {
            return null;
        }
        String trimmed = rawYear.trim();
        if (!trimmed.matches("\\d+")) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
