package com.sism.execution.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 指标最新一轮「可见填报」的附件汇总（2026-10-28）。
 *
 * 口径与页面一致：
 * ① 填报组织本人：最新一轮（含尚未发起审批的草稿）；
 * ② 上级/其他组织：最新一轮非草稿（上一轮已上报快照）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorLatestAttachmentResponse {

    private Long indicatorId;
    private Long reportId;
    /** 填报期间 YYYYMM */
    private String reportMonth;
    /** 填报日期 YYYY-MM-DD（提交时间优先，草稿回退到填报期间） */
    private String fillDate;
    /** 该查看人可见轮次中的序号（1 起） */
    private Integer round;
    private String status;
    private List<ReportAttachmentResponse> attachments;
}
