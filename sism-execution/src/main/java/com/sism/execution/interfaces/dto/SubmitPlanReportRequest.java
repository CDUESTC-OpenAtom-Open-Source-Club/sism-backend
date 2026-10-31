package com.sism.execution.interfaces.dto;

import lombok.Data;

/**
 * SubmitPlanReportRequest - 提交报告请求DTO（可选）。
 *
 * <p>2026-10-31 修复（重报说明被丢弃，图05）：驳回后重报时前端强制收集的
 * 「重报说明」随提交请求透传进工作流提交步备注。字段名与前端约定为
 * {@code comment}；整个 body 可省略，comment 也可经 query 参数传递（二选一，
 * body 优先）。
 */
@Data
public class SubmitPlanReportRequest {

    /** 重报/提交说明，可空。为空时后端保留「提交人重新提交（未填写说明）」兜底文案。 */
    private String comment;
}
