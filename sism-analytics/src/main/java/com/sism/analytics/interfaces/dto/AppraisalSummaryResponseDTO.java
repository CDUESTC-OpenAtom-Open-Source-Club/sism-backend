package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AppraisalSummaryResponseDTO - 数据看板「审核鉴定」汇总（2026-11-17 用户反馈第 1 条修复；
 * 2026-11-18 用户拍板升级为「按月快照 + 未出结果置灰」，废除沿用逻辑）。
 *
 * <p>一次请求同时供三处使用，保证同一份审核结果在看板上口径一致（全部为选中月当月快照）：</p>
 * <ul>
 *   <li>{@code departments}：职能部门视角「指标完成情况分布」（选中月快照，
 *       只统计该月 APPROVED 鉴定；审批中部门 monthState=IN_REVIEW 鉴定值为 0）</li>
 *   <li>{@code departmentMonths}：同上，1 月至选中月份逐月独立快照（各月互不沿用，
 *       用于下钻查看月度趋势）</li>
 *   <li>{@code laggingTasks}：「TOP 滞后任务响应清单」（选中月 APPROVED 鉴定 = DELAYED；
 *       职能部门线与学院线全量记录）</li>
 *   <li>{@code annual}：「任务完成简报」汇总（随选中月快照走）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "数据看板审核鉴定汇总（按月快照口径）")
public class AppraisalSummaryResponseDTO {

    @Schema(description = "年份")
    private int year;

    @Schema(description = "月份（1-12）")
    private int month;

    @Schema(description = "该年度最近一个有鉴定数据的月份（APPROVED 且等级非空；无数据时返回 0）；"
            + "前端默认月份无数据时可据此跳到最近有数据的月份（审批中/未填报月份不算有鉴定数据）")
    private int latestMonth;

    @Schema(description = "职能部门鉴定等级计数（选中月当月快照，只统计该月 APPROVED 鉴定；"
            + "审批中部门鉴定值为 0 且 monthState=IN_REVIEW，未填报部门不返回，各月互不沿用）")
    private List<DepartmentLevelDTO> departments;

    @Schema(description = "1 月至选中月份的逐月独立快照（下钻趋势用；各月互不沿用）")
    private List<DepartmentLevelMonthDTO> departmentMonths;

    @Schema(description = "滞后任务清单（选中月 APPROVED 鉴定为 DELAYED，按权重降序；"
            + "职能部门线与学院线全量记录，责任主体取指标目标组织）")
    private List<LaggingTaskDTO> laggingTasks;

    @Schema(description = "鉴定等级汇总（简报口径随选中月快照走，只统计该月 APPROVED 鉴定）")
    private AnnualLevelSummaryDTO annual;
}
