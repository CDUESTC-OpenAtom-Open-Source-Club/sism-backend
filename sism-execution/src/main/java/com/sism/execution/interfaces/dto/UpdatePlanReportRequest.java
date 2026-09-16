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

    private String issues;

    private String nextPlan;


    private Long operatorUserId;

    private List<UpdatePlanReportIndicatorDetailRequest> indicatorDetails;
}
