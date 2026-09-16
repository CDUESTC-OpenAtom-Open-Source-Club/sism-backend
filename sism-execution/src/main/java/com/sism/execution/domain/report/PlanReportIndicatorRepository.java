package com.sism.execution.domain.report;

import java.util.List;
import java.util.Map;

/**
 * plan_report_indicator 运行时明细仓储。
 */
public interface PlanReportIndicatorRepository {

    Long upsertDraftIndicator(Long reportId, Long indicatorId, Integer progress, String comment);

    void attachFiles(Long planReportIndicatorId, java.util.List<Long> attachmentIds, Long createdBy);

    List<PlanReportIndicatorSnapshot> findByReportId(Long reportId);

    Map<Long, List<PlanReportIndicatorSnapshot>> findByReportIds(List<Long> reportIds);
}
