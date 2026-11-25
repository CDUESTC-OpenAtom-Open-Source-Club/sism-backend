package com.sism.main.interfaces.dto;

import java.util.List;

/**
 * 2026-11-25（指标填报导入式上报 · 学院侧）：填报导入的 DTO 契约。
 *
 * <p>与既有 {@link BusinessImportDtos}（导入<b>下发</b>：建线、下发子指标）是<b>两条独立链路</b>：
 * 本 DTO 服务的是「学院填报人用 Excel 一次性完成本月的<b>指标填报</b>」——
 * 落库目标是 {@code plan_report_indicator}（自评等级 + 进度说明），不是 plan / indicator。
 *
 * <p>口径来源：指标填报导入模板（导入式上报·单归口 / 多归口）及其「填写说明」页。
 */
public final class ReportFillImportDtos {

    private ReportFillImportDtos() {
    }

    /**
     * 自评等级白名单。
     *
     * <p>2026-11-25 用户定案：等级文案<b>强制统一为「超前完成 / 正常 / 滞后」</b>——
     * 与系统既有文案一致（工作流提示「请完成审核进度鉴定（超前完成 / 正常 / 滞后）」）。
     * 刻意<b>不</b>兼容「超前」等其他写法：{@code ProgressLevel.normalize} 对无法识别的值
     * 静默返回 null（不报错），会让非法等级拖到提交时才炸，因此本层做严格白名单校验。
     */
    public enum ReportFillLevel {
        AHEAD("超前完成"),
        NORMAL("正常"),
        DELAYED("滞后");

        private final String label;

        ReportFillLevel(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** 按中文文案精确匹配（去首尾空白，含全角空格）；不命中返回 null。 */
        public static ReportFillLevel fromLabel(String value) {
            if (value == null) {
                return null;
            }
            // 2026-11-25（审查修正）：全角空格 U+3000 不被 trim() 处理，
            // 「正常　」会被判非法并整行点名，用户看不见那个字符。
            String trimmed = value.replace('\u3000', ' ').trim();
            for (ReportFillLevel level : values()) {
                if (level.label.equals(trimmed)) {
                    return level;
                }
            }
            return null;
        }

        /** 供错误文案使用的合法值清单。 */
        public static String legalLabels() {
            StringBuilder builder = new StringBuilder();
            for (ReportFillLevel level : values()) {
                if (builder.length() > 0) {
                    builder.append(" / ");
                }
                builder.append(level.label);
            }
            return builder.toString();
        }
    }

    /**
     * 预览的一行（一条待填报的子指标）。
     *
     * @param rowNo         文件中的行号（表头之后从 1 起）
     * @param college       「学院」列原文
     * @param functionalDept 「归口职能部门」列原文
     * @param indicatorName 「子指标名称」列原文（定位键之一）
     * @param weight        「权重」列原文——<b>只读参考，不落库</b>
     * @param selfRating    「自评等级」列原文（校验通过时为中文合法值）
     * @param level         归一后的等级码（AHEAD/NORMAL/DELAYED），校验不通过时为 null
     * @param remark        「进度说明」列原文
     * @param indicatorId   定位到的系统子指标 id（定位失败时为 null）
     * @param matchedName   命中的系统子指标名称（定位失败时为 null）
     * @param errors        行级错误（定位失败 / 等级非法 / 说明为空……）
     * @param warnings      行级警告
     */
    public record ReportFillRow(
            int rowNo,
            String college,
            String functionalDept,
            String indicatorName,
            String weight,
            String selfRating,
            String level,
            String remark,
            Long indicatorId,
            String matchedName,
            List<String> errors,
            List<String> warnings
    ) {
        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }
    }

    /**
     * 一个归口线的分组（= 一份月报）。
     *
     * <p>分组依据 = 文件内「归口职能部门」列；每组独立校验、独立落库、独立提交上报
     * （一条线失败不影响其他线）。
     *
     * @param functionalOrgId 归口部门 id（组级错误时可能为 null）
     * @param functionalOrgName 归口部门名称
     * @param planId           该 (周期, 学院, 归口) 对应的计划线 id（找不到时为 null）
     * @param rowsCount        组内行数
     * @param willSubmit       本组是否可提交（组级阻断或不属于所选归口时为 false）
     * @param groupError       组级<b>错误</b>文案（非空 = 该组不可提交，前端标红）
     * @param skipReason       组级<b>跳过</b>原因（非空且 groupError 为空 = 该组将跳过，前端标灰）
     * @param rows             组内预览行
     */
    public record ReportFillGroup(
            Long functionalOrgId,
            String functionalOrgName,
            Long planId,
            int rowsCount,
            boolean willSubmit,
            String groupError,
            String skipReason,
            List<ReportFillRow> rows
    ) {
    }

    /**
     * 预览响应。
     *
     * @param batchId          批次 id（commit 时回传）
     * @param cycleId          考核周期 id
     * @param reportMonth      归属月份（yyyyMM）
     * @param targetCollegeOrgId 登录人所在学院 org id
     * @param targetCollegeName 登录人所在学院名称
     * @param groups           按归口部门拆出的分组
     * @param hasSubmittableGroup 是否至少有一条可提交的归口线（前端据此决定「确认导入」能否点）
     * @param blocking         整单不可提交（= 没有任何可提交的组）；单组出错不等于整单阻断
     * @param confirmToken     提交确认令牌
     */
    public record ReportFillPreviewResponse(
            String batchId,
            Long cycleId,
            String reportMonth,
            Long targetCollegeOrgId,
            String targetCollegeName,
            List<ReportFillGroup> groups,
            boolean hasSubmittableGroup,
            boolean blocking,
            String confirmToken
    ) {
    }

    /**
     * 提交请求。
     *
     * @param confirmToken 预览返回的确认令牌
     * @param comment      上报说明（写入提交步备注，可空）
     */
    public record ReportFillCommitRequest(
            String confirmToken,
            String comment
    ) {
    }

    /** 单个归口线的提交结果。 */
    public enum ReportFillGroupStatus {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    /**
     * @param reportId 生成的月报 id（跳过/失败时可能为 null）
     * @param rowsCount 落库行数
     * @param message  结果说明（跳过原因 / 失败原因 / 提交结果）
     */
    public record ReportFillGroupResult(
            Long functionalOrgId,
            String functionalOrgName,
            ReportFillGroupStatus status,
            Long reportId,
            int rowsCount,
            String message
    ) {
    }

    /** 提交汇总。 */
    public record ReportFillCommitSummary(
            int success,
            int skipped,
            int failed
    ) {
    }

    /** 提交响应（逐组汇报，组间独立事务）。 */
    public record ReportFillCommitResponse(
            List<ReportFillGroupResult> results,
            ReportFillCommitSummary summary
    ) {
    }
}
