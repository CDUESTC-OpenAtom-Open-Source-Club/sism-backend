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

    public record MilestoneImportValue(
            String name,
            LocalDateTime dueAt,
            Integer targetProgress
    ) {
    }

    public record NormalizedImportRow(
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
            Long parentIndicatorId,
            List<MilestoneImportValue> milestones
    ) {
        public Map<String, Object> toMap() {
            return Map.ofEntries(
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
                    Map.entry("parentIndicatorId", parentIndicatorId == null ? "" : parentIndicatorId),
                    Map.entry("milestones", milestones == null ? List.of() : milestones)
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
            ImportWorkflowResult workflow
    ) {
    }
}
