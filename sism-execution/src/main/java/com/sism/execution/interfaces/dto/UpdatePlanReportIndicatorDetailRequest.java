package com.sism.execution.interfaces.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdatePlanReportIndicatorDetailRequest {

    private Long indicatorId;

    private String title;

    /** 完成情况描述（填报正文，同时落 comment 与 description 列） */
    private String content;

    private String summary;

    private Integer progress;

    private String issues;

    private String nextPlan;

    /**
     * 自评进度等级（P1 上报链改造）：AHEAD=超前 / NORMAL=正常 / DELAYED=延期。
     * 服务端用 ProgressLevel 归一校验；可空（兼容存量调用）。
     */
    private String selfRating;

    private List<Long> attachmentIds;
}
