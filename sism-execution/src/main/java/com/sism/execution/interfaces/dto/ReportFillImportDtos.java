package com.sism.execution.interfaces.dto;

import java.util.List;

/**
 * 2026-11-25（二级学院填报导入）：学院填报页「导入 / 批量导入」的请求与响应契约。
 *
 * <p>模板 7 列：序号 / 学院（须与登录学院一致）/ 归口职能部门 / 子指标名称 /
 * 权重（系统只读）/ 自评等级（点选填写）/ 进度说明（请填写）。
 * 仅「自评等级」「进度说明」允许填写，其余列解析时只做校验、不落库。
 */
public final class ReportFillImportDtos {

    private ReportFillImportDtos() {
    }

    public enum RowAction {
        /** 该行将写入草稿（新建或覆盖） */
        UPDATE,
        /** 与当前草稿一致，无需写入 */
        SKIP,
        /** 校验失败，不写入 */
        ERROR
    }

    public record PreviewRow(
            int rowNo,
            Long indicatorId,
            String indicatorName,
            String selfRating,
            String selfRatingLabel,
            String description,
            String existingSelfRating,
            String existingDescription,
            String weight,
            RowAction action,
            List<String> errors,
            List<String> warnings
    ) {
    }

    public record PreviewGroup(
            Long functionalOrgId,
            String functionalOrgName,
            Long planId,
            String planStatus,
            Long reportId,
            String reportStatus,
            List<PreviewRow> rows,
            List<String> errors
    ) {
    }

    public record PreviewSummary(
            int totalRows,
            int validRows,
            int skipRows,
            int errorRows
    ) {
    }

    public record PreviewResponse(
            String batchId,
            String fileName,
            Long cycleId,
            String reportMonth,
            Long targetOrgId,
            String targetOrgName,
            PreviewSummary summary,
            List<PreviewGroup> groups,
            String confirmToken
    ) {
    }

    public record CommitRequest(
            String confirmToken,
            String comment
    ) {
    }

    public record CommitGroupResult(
            Long functionalOrgId,
            String functionalOrgName,
            Long planId,
            Long reportId,
            int savedRows,
            String status,
            String message
    ) {
    }

    public record CommitResponse(
            String batchId,
            String status,
            int totalSaved,
            int blockedGroups,
            List<CommitGroupResult> groups
    ) {
    }
}
