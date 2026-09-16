package com.sism.execution.interfaces.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdatePlanReportIndicatorDetailRequest {

    private Long indicatorId;

    private String title;

    private String content;

    private String summary;

    private Integer progress;

    private String issues;

    private String nextPlan;


    private List<Long> attachmentIds;
}
