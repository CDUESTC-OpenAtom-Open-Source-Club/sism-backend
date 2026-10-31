package com.sism.execution.interfaces.dto;

import lombok.Data;

import java.util.List;

/**
 * UpdatePlanReportRequest - 更新计划报告请求DTO
 * 用于接收更新报告内容的请求参数
 */
@Data
public class UpdatePlanReportRequest {

    private String title;

    private Long indicatorId;

    private String content;

    private String summary;

    private Integer progress;

    /**
     * 2026-10-30 修复（等级制填报保存必失败）：单条填报路径的自评进度等级。
     *
     * <p>2026-10-07 裁决：进度改为等级制（AHEAD/NORMAL/DELAYED），百分比 progress 仅作占位。
     * 原 DTO 没有等级字段，导致单条路径无法表达等级制语义，后端只能按百分比路径校验
     * 「进度必须大于真实进度」——等级制下 progress 恒为 0 → `0 <= 0` 恒成立 → 保存必 400。
     */
    private String selfRating;

    private String issues;

    private String nextPlan;


    private Long operatorUserId;

    private List<UpdatePlanReportIndicatorDetailRequest> indicatorDetails;
}
