package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DepartmentLevelMonthDTO - 部门视角「指标完成情况分布」某个月的快照（用于下钻月度趋势）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "职能部门鉴定等级月度快照")
public class DepartmentLevelMonthDTO {

    @Schema(description = "月份（1-12）")
    private int month;

    @Schema(description = "该月有已鉴定上报记录的职能部门列表（无数据的部门不返回）")
    private List<DepartmentLevelDTO> departments;
}
