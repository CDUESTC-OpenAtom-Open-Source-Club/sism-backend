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
            // 2026-11-08（学院指标单层口径）：学院模板新增「归口职能部门」列。
            // 学院指标改成「战略部 → 学院」直发后没有父级，学院上报链第 3 环
            // 「分管职能部门负责人审批」的席位来源只能由这一列显式指定。
            // 这里存**部门名称**（文件里就是名称），落库时再按名称查 sys_org 换 id，
            // 原因：预览阶段用户看到的是名称，提前转 id 会让错误提示不可读。
            String functionalOrg
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
                    Map.entry("functionalOrg", functionalOrg == null ? "" : functionalOrg)
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
            String confirmToken
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
}
