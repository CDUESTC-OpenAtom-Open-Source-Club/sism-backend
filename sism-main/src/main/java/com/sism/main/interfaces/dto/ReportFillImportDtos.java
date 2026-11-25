package com.sism.main.interfaces.dto;

import java.util.List;

/**
 * 2026-11-25（指标填报导入式上报）：填报导入的 DTO 契约。
 *
 * <p>标准模板以需求方「SISM-导入模板-04/05/06」为准，三份共用同一套 8 列结构，
 * 只有「第 3 列」的语义随流向不同：
 * <ul>
 *   <li>04 职能部门上报：责任部门（来源·部门）→ <b>上报目标（战略发展部）</b></li>
 *   <li>05/06 学院上报：责任部门（来源·学院）→ <b>目标部门（归口职能部门）</b></li>
 * </ul>
 *
 * <p>与既有 {@link BusinessImportDtos}（导入<b>下发</b>：建线、下发子指标）是<b>两条独立链路</b>：
 * 本 DTO 服务的是「填报人用 Excel 一次性完成本月的<b>指标填报</b>」——
 * 落库目标是 {@code plan_report_indicator}（自评等级 + 进度说明），不是 plan / indicator。
 */
public final class ReportFillImportDtos {

    private ReportFillImportDtos() {
    }

    /**
     * 自评等级白名单。
     *
     * <p>2026-11-25 用户定案：等级文案<b>强制统一为「超前完成 / 正常 / 滞后」</b>——
     * 与系统既有文案一致，也与模板第 8 列「（枚举来源）」给出的枚举一致。
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
            // 全角空格 U+3000 不被 trim() 处理，「正常　」会被判非法并整行点名，用户看不见那个字符
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
     * @param rowNo          Excel <b>物理行号</b>（1-based，含表头行）
     * @param responsibleDept 「责任部门（来源）」列原文——必须等于登录人所在组织
     * @param targetDept     「目标部门 / 上报目标」列原文——这条填报上报到哪
     * @param indicatorName  「子指标名称」列原文（定位键之一）
     * @param weight         「权重」列原文——<b>只读参考，不落库</b>
     * @param selfRating     「自评等级」列原文（校验通过时为中文合法值）
     * @param level          归一后的等级码（AHEAD/NORMAL/DELAYED），校验不通过时为 null
     * @param remark         「进度说明」列原文
     * @param indicatorId    定位到的系统子指标 id（定位失败时为 null）
     * @param matchedName    命中的系统子指标名称（定位失败时为 null）
     * @param errors         行级错误
     * @param warnings       行级警告
     */
    public record ReportFillRow(
            int rowNo,
            String responsibleDept,
            String targetDept,
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
     * 一条「线」的分组（= 一份月报）。
     *
     * <p>学院上报按「目标部门（归口职能部门）」拆线，每条线独立校验、独立落库、独立提交上报
     * （一条线失败不影响其他线）；职能部门上报只有一条线（目标恒为战略发展部）。
     *
     * @param targetOrgId          目标部门 org id（04 = 战略发展部；05/06 = 归口职能部门）
     * @param targetOrgName        目标部门名称
     * @param planId               对应的计划 id（找不到时为 null）
     * @param rowsCount            组内行数
     * @param willSubmit           本组是否可提交
     * @param groupError           组级<b>错误</b>文案（非空 = 不可提交，标红）
     * @param skipReason           组级<b>跳过</b>原因（非空且 groupError 为空 = 将跳过，标灰）
     * @param existingReportId     同月同线已有填报的 report id（无则为 null）——
     *                             前端据此提示「将覆盖 / 将跳过」，与 commit 的 conflictMode 行为保持一致
     * @param existingReportStatus 同月同线已有填报的状态（DRAFT/SUBMITTED/APPROVED/REJECTED）
     * @param rows                 组内预览行
     */
    public record ReportFillGroup(
            Long targetOrgId,
            String targetOrgName,
            Long planId,
            int rowsCount,
            boolean willSubmit,
            String groupError,
            String skipReason,
            Long existingReportId,
            String existingReportStatus,
            List<ReportFillRow> rows
    ) {
    }

    /**
     * 预览响应。
     *
     * @param reportType           上报类型：{@code FUNC_DEPT}（04 职能部门）/{@code COLLEGE}（05/06 学院）
     * @param batchId              批次 id（commit 时回传）
     * @param cycleId              考核周期 id
     * @param reportMonth          归属月份（yyyyMM）
     * @param responsibleOrgId     责任部门 org id（= 登录人所在组织）
     * @param responsibleOrgName   责任部门名称
     * @param groups               分组
     * @param hasSubmittableGroup  是否至少有一条可提交的线（前端据此决定「确认导入」能否点）
     * @param blocking             整单不可提交（= 没有任何可提交的组）
     * @param confirmToken         提交确认令牌
     */
    public record ReportFillPreviewResponse(
            String reportType,
            String batchId,
            Long cycleId,
            String reportMonth,
            Long responsibleOrgId,
            String responsibleOrgName,
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
     * @param conflictMode 同月同线已有填报时的处理方式：
     *                     {@code OVERWRITE} = 覆盖已有（复用该 report、重置草稿后重新提交审批）；
     *                     {@code SKIP}（默认，空值同义）= 跳过该线，不写入
     */
    public record ReportFillCommitRequest(
            String confirmToken,
            String comment,
            String conflictMode
    ) {
    }

    /** 单条线的提交结果。 */
    public enum ReportFillGroupStatus {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    /**
     * @param reportId 生成的月报 id（跳过/失败时可能为 null）
     * @param rowsCount 落库行数
     * @param message  结果说明
     */
    public record ReportFillGroupResult(
            Long targetOrgId,
            String targetOrgName,
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
