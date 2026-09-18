package com.sism.execution.domain.report;

import java.util.List;
import java.util.Map;

/**
 * plan_report_indicator 运行时明细仓储。
 */
public interface PlanReportIndicatorRepository {

    Long upsertDraftIndicator(Long reportId, Long indicatorId, Integer progress, String comment);

    /**
     * P1 上报链改造：携带自评进度等级（AHEAD/NORMAL/DELAYED）与完成情况描述。
     */
    Long upsertDraftIndicator(Long reportId,
                              Long indicatorId,
                              Integer progress,
                              String comment,
                              String selfRating,
                              String description);

    /**
     * P1 上报链改造：报告审批通过时，将终审鉴定等级回写到该报告全部明细行
     * （ appraisal_level 仅在比原值更新时覆盖，保证多轮上报互不污染）。
     */
    void applyAppraisalLevel(Long reportId, String appraisalLevel);

    void attachFiles(Long planReportIndicatorId, java.util.List<Long> attachmentIds, Long createdBy);

    List<PlanReportIndicatorSnapshot> findByReportId(Long reportId);

    Map<Long, List<PlanReportIndicatorSnapshot>> findByReportIds(List<Long> reportIds);
}
