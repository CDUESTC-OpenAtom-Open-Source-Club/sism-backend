package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DepartmentLevelDTO - 单个职能部门「当月快照」等级计数。
 *
 * <p>2026-11-18 用户拍板（按月快照）：鉴定值只来自该月 APPROVED 月报；
 * 已填报但审批中的部门鉴定值全 0 且 {@code monthState=IN_REVIEW}（前端置灰标「审批中」）；
 * 当月未填报的部门不返回（无鉴定值，前端按缺省置灰）。各月互不沿用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "职能部门指标鉴定等级计数（当月快照）")
public class DepartmentLevelDTO {

    @Schema(description = "职能部门 ID")
    private long orgId;

    @Schema(description = "职能部门名称")
    private String orgName;

    @Schema(description = "超前完成数")
    private long ahead;

    @Schema(description = "正常推进数")
    private long normal;

    @Schema(description = "滞后数（上级鉴定 DELAYED）")
    private long delayed;

    @Schema(description = "计数合计（ahead + normal + delayed）")
    private long total;

    @Schema(description = "已填报标记：true=该部门当月有已提交/审批中的月报（配合 monthState 区分审批状态）；"
            + "当月未填报的部门不出现在列表中")
    private boolean reported;

    @Schema(description = "当月快照状态：APPROVED=该月月报已审批通过（鉴定值有效）；"
            + "IN_REVIEW=该月已填报但审批未通过（鉴定值无效，前端置灰标「审批中」）；"
            + "NOT_REPORTED=该月未填报（不出现在列表中，枚举值供前端兜底）")
    private MonthState monthState;
}
