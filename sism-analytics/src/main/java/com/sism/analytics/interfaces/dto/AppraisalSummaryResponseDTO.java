package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AppraisalSummaryResponseDTO - 数据看板「审核鉴定」汇总（2026-11-17 用户反馈第 1 条修复）。
 *
 * <p>一次请求同时供三处使用，保证同一份审核结果在看板上口径一致：</p>
 * <ul>
 *   <li>{@code departments}：职能部门视角「指标完成情况分布」（选中月份快照）</li>
 *   <li>{@code departmentMonths}：同上，用于下钻查看 1 月至选中月份的月度趋势</li>
 *   <li>{@code laggingTasks}：「TOP 滞后任务响应清单」（年度最近一次鉴定 = DELAYED）</li>
 *   <li>{@code annual}：「任务完成简报」年度汇总（年度最近一次鉴定）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "数据看板审核鉴定汇总")
public class AppraisalSummaryResponseDTO {

    @Schema(description = "年份")
    private int year;

    @Schema(description = "月份（1-12）")
    private int month;

    @Schema(description = "该年度最近一个有鉴定数据的月份（无数据时返回 0）；前端默认月份无数据时可据此跳到最近有数据的月份")
    private int latestMonth;

    @Schema(description = "职能部门鉴定等级计数（截至选中月份的最近一次鉴定）")
    private List<DepartmentLevelDTO> departments;

    @Schema(description = "1 月至选中月份的部门等级快照（下钻趋势用）")
    private List<DepartmentLevelMonthDTO> departmentMonths;

    @Schema(description = "滞后任务清单（最近一次鉴定为 DELAYED，按权重降序）")
    private List<LaggingTaskDTO> laggingTasks;

    @Schema(description = "年度鉴定等级汇总（简报口径）")
    private AnnualLevelSummaryDTO annual;
}
