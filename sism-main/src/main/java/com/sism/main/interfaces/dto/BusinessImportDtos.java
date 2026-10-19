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
            // 2026-10-19 用户定案：「序号」列必填（1、2、3…短编号），是导入时标定行身份的唯一用户可见编号；
            // 系统在预览时把它还原为内部ID（数据库主键）做唯一性匹配——同名指标各按各的序号归属。
            // 「内部ID」列从导入导出表移除，不再解析。
            Integer seq,
            String department,
            String college,
            String taskType,
            String strategicTask,
            String parentStrategicTask,
            String parentIndicator,
            String indicatorName,
            String indicatorType,
            BigDecimal weight,
            String remark,
            Long parentIndicatorId
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
                    Map.entry("indicatorName", indicatorName == null ? "" : indicatorName),
                    Map.entry("indicatorType", indicatorType == null ? "" : indicatorType),
                    Map.entry("weight", weight == null ? "" : weight),
                    Map.entry("remark", remark == null ? "" : remark),
                    Map.entry("parentIndicatorId", parentIndicatorId == null ? "" : parentIndicatorId)
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
