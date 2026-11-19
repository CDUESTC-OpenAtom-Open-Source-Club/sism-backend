package com.sism.main.interfaces.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class BusinessImportDtos {

    private BusinessImportDtos() {
    }

    public enum ImportType {
        STRATEGIC_TASK,
        DISTRIBUTION
    }

    public enum ImportAction {
        CREATE,
        UPDATE,
        SKIP,
        ERROR
    }

    public enum ConflictMode {
        APPEND,
        UPDATE,
        REPLACE_SCOPE
    }

    public record FieldMapping(
            String sourceColumn,
            String targetField,
            String confidence
    ) {
    }

    public record ImportSummary(
            int totalRows,
            int validRows,
            int createRows,
            int updateRows,
            int skipRows,
            int errorRows,
            int warningRows
    ) {
    }

    public record NormalizedImportRow(
            // 2026-10-31 层级化定案：「序号」升级为组号（职能部门=战略任务组、学院=父级核心指标组），
            // 组首行必填、组内留空=同上（sticky 向下继承）；不再做行身份匹配，
            // 落库匹配键按名称 =（任务, 核心指标名）/（父级核心指标, 子指标名）。
            Integer seq,
            String department,
            String college,
            String taskType,
            String strategicTask,
            String parentStrategicTask,
            String parentIndicator,
            // 2026-10-31 终版表头：「核心指标类型」=父指标自身类型（定量/定性），
            // 系统预填、解析时只读校验（与系统父指标实际类型不一致→警告不阻断），不落库
            String parentIndicatorType,
            String indicatorName,
            String indicatorType,
            BigDecimal weight,
            String remark,
            Long parentIndicatorId,
            // 2026-11-19（学院「全部导入」）：「职能部门」列 = 该行指标由哪个职能部门下发。
            // 学院模板新增列（别名「归口部门」），单条导入路径不使用该字段（零行为变更）；
            // 全部导入路径用它做「拆批依据 + 与父级核心指标归属的交叉校验」。
            String functionalDept
    ) {
        public Map<String, Object> toMap() {
            return Map.ofEntries(
                    Map.entry("seq", seq == null ? "" : seq),
                    Map.entry("department", department == null ? "" : department),
                    Map.entry("college", college == null ? "" : college),
                    Map.entry("taskType", taskType == null ? "" : taskType),
                    Map.entry("strategicTask", strategicTask == null ? "" : strategicTask),
                    Map.entry("parentStrategicTask", parentStrategicTask == null ? "" : parentStrategicTask),
                    Map.entry("parentIndicator", parentIndicator == null ? "" : parentIndicator),
                    Map.entry("parentIndicatorType", parentIndicatorType == null ? "" : parentIndicatorType),
                    Map.entry("indicatorName", indicatorName == null ? "" : indicatorName),
                    Map.entry("indicatorType", indicatorType == null ? "" : indicatorType),
                    Map.entry("weight", weight == null ? "" : weight),
                    Map.entry("remark", remark == null ? "" : remark),
                    Map.entry("parentIndicatorId", parentIndicatorId == null ? "" : parentIndicatorId),
                    Map.entry("functionalDept", functionalDept == null ? "" : functionalDept)
            );
        }
    }

    public record ImportRowPreview(
            int rowNo,
            ImportAction action,
            String businessKey,
            NormalizedImportRow normalized,
            Map<String, String> source,
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

    public record ImportPreviewResponse(
            String batchId,
            ImportType type,
            String fileName,
            String sheetName,
            Long targetOrgId,
            String targetOrgName,
            ImportSummary summary,
            List<FieldMapping> fieldMappings,
            List<ImportRowPreview> rows,
            boolean blocking,
            String confirmToken,
            // 2026-11-17（学院线粒度改造）：本批次归属的「线」的归口部门（线身份的一部分）。
            // 学院导入必非空（由文件父级反查 + 入参交叉校验确定）；职能部门导入为 null（无线概念）。
            Long functionalOrgId
    ) {
    }

    public record ImportCommitRequest(
            String confirmToken,
            ConflictMode conflictMode,
            Boolean autoSubmitAndApprove,
            String comment
    ) {
    }

    public record ImportWorkflowResult(
            Boolean autoSubmitAndApprove,
            String workflowCode,
            Long instanceId,
            String status,
            Integer approvedSteps,
            String failedStep,
            String message
    ) {
    }

    public record ImportCommitResponse(
            String batchId,
            String status,
            int createdCount,
            int updatedCount,
            int skippedCount,
            // 2026-10-19：真覆盖模式软删除的旧指标数（不在文件覆盖集合中的既有指标）
            int deletedCount,
            ImportWorkflowResult workflow
    ) {
    }

    // ==================== 2026-11-19（学院「全部导入」）：按归口部门分组拆批 ====================

    /**
     * 2026-11-19：学院「全部导入」——一条线在 (cycle, 归口部门, 学院) 维度上的状态。
     *
     * <ul>
     *   <li>NONE：本周期该 (归口, 学院) 还没有线 → 本次将建线并下发；</li>
     *   <li>DRAFT：已有草稿线 → 本次将落库并可下发；</li>
     *   <li>PENDING / IN_REVIEW：线上有审批实例在审 → 该组归错误类，不可下发；</li>
     *   <li>DISTRIBUTED：线已下发 → 该组「将跳过」。</li>
     * </ul>
     */
    public enum AllImportLineStatus {
        NONE,
        DRAFT,
        PENDING,
        IN_REVIEW,
        DISTRIBUTED
    }

    /**
     * 2026-11-19：全部导入预览的一个归口分组。
     *
     * @param functionalOrgId   归口部门 id（无法识别的行归入 unassignedRows，恒为 null）
     * @param functionalOrgName 归口部门名称
     * @param lineStatus        该 (cycle, 归口, 学院) 线的状态
     * @param willDispatch      是否会下发（false = 跳过或阻断）
     * @param skipReason        跳过原因（仅 willDispatch=false 且非错误时给出）
     * @param groupError        组级错误（权重≠100 / 该线在审 / 行级错误等）
     * @param basicWeightTotal  该组基础性权重合计
     * @param rowsCount         该组行数
     * @param rows              该组的预览行（既有 ImportRowPreview 形状）
     */
    public record AllImportGroup(
            Long functionalOrgId,
            String functionalOrgName,
            AllImportLineStatus lineStatus,
            boolean willDispatch,
            String skipReason,
            String groupError,
            BigDecimal basicWeightTotal,
            int rowsCount,
            List<ImportRowPreview> rows
    ) {
    }

    /**
     * 2026-11-19：全部导入预览响应。
     *
     * @param groups          按归口部门拆出的分组（每组独立走既有单条导入链路）
     * @param unassignedRows  无法识别归属部门的行（行级错误「无法识别归属部门」）
     * @param blocking        是否存在阻断错误（存在时不允许提交）
     */
    public record AllImportPreviewResponse(
            String batchId,
            Long cycleId,
            Long targetCollegeOrgId,
            String targetCollegeName,
            List<AllImportGroup> groups,
            List<ImportRowPreview> unassignedRows,
            boolean blocking,
            String confirmToken
    ) {
    }

    /** 2026-11-19：全部导入单个分组的执行结果。 */
    public enum AllImportGroupStatus {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    /**
     * 2026-11-19：全部导入单个分组的执行结果。
     *
     * @param planId    该组落到的线 id（跳过/失败时可能为 null）
     * @param rowsCount 该组行数
     * @param message   说明（跳过原因 / 失败原因 / 工作流结果）
     */
    public record AllImportGroupResult(
            Long functionalOrgId,
            String functionalOrgName,
            AllImportGroupStatus status,
            Long planId,
            int rowsCount,
            String message
    ) {
    }

    /** 2026-11-19：全部导入提交汇总。 */
    public record AllImportCommitSummary(
            int success,
            int skipped,
            int failed
    ) {
    }

    /**
     * 2026-11-19：全部导入提交响应（逐组汇报，组间独立事务）。
     */
    public record AllImportCommitResponse(
            List<AllImportGroupResult> results,
            AllImportCommitSummary summary
    ) {
    }
}
