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
     * P1 上报链改造：报告审批通过时，将终审审核进度回写到该报告全部明细行
     * （ appraisal_level 仅在比原值更新时覆盖，保证多轮上报互不污染）。
     *
     * @deprecated 2026-10-24 用户定案：整体鉴定废弃，改用 {@link #applyAppraisalLevelForIndicators}。
     */
    @Deprecated
    void applyAppraisalLevel(Long reportId, String appraisalLevel);

    /**
     * 2026-10-24 用户定案（指标级鉴定）：把逐指标审核进度回写到指定明细行，
     * 只触碰给出的 indicator_id，未评定的指标保持原值/空。
     */
    void applyAppraisalLevelForIndicators(Long reportId, List<Long> indicatorIds, String appraisalLevel);

    /**
     * 2026-10-28 用户定案：读取某报告轮次下已评定的逐指标审核进度。
     * 用于「上级覆盖下级、未改动沿用上一级」的审批校验。
     */
    Map<Long, String> findAppraisalLevelsByReport(Long reportId);

    /**
     * 2026-10-30 用户定案：审批拦截文案要报指标名称（indicator_desc）、不报指标 ID。
     * 返回该报告轮次下 指标ID→指标名称 映射（关联 indicator 表；缺失兜底「未知指标」）。
     */
    Map<Long, String> findIndicatorNamesByReport(Long reportId);

    /**
     * 2026-10-28 用户定案：审批链上的行内鉴定——直接更新当前报告轮次的单指标等级。
     * level 为 null 表示恢复未评定。
     */
    void updateAppraisalLevel(Long reportId, Long indicatorId, String appraisalLevel);

    void attachFiles(Long planReportIndicatorId, java.util.List<Long> attachmentIds, Long createdBy);

    List<PlanReportIndicatorSnapshot> findByReportId(Long reportId);

    Map<Long, List<PlanReportIndicatorSnapshot>> findByReportIds(List<Long> reportIds);
}
