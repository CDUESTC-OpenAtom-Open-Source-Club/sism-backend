package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DepartmentLevelMonthDTO - 部门视角「指标完成情况分布」某个月的独立快照（用于下钻月度趋势）。
 * 2026-11-18 拍板：各月互不沿用——第 m 月的快照只由第 m 月自己的 APPROVED 月报决定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "职能部门鉴定等级月度快照（按月独立，互不沿用）")
public class DepartmentLevelMonthDTO {

    @Schema(description = "月份（1-12）")
    private int month;

    @Schema(description = "该月有已填报月报的职能部门列表（全量/职能部门视角下只含部门自己上报的指标；"
            + "鉴定值只算该月 APPROVED；审批中部门鉴定值为 0 且 monthState=IN_REVIEW；"
            + "该月未填报/无已鉴定数据的部门不返回）")
    private List<DepartmentLevelDTO> departments;
}
