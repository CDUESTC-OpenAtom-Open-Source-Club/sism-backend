package com.sism.execution.interfaces.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量查询指标的鉴定进度上下文（2026-10-28）。
 * 用于列表页/填报页判断「当前用户是否当前审批节点的负责人」以及回显当前等级。
 */
@Data
public class IndicatorAppraisalContextRequest {

    @NotEmpty(message = "indicatorIds 不能为空")
    @Size(max = 2000, message = "单次最多查询 2000 个指标")
    private List<Long> indicatorIds;
}
