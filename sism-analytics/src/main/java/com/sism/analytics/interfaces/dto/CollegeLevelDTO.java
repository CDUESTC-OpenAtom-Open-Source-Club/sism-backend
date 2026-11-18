package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CollegeLevelDTO - 单个学院的鉴定等级计数 + 督办上下文。
 * Matches frontend dashboard college-levels contract
 * (2026-11-18 拍板口径：按月快照，只有 APPROVED 月报计入鉴定值)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "学院指标完成情况计数")
public class CollegeLevelDTO {

    @Schema(description = "组织ID")
    private long orgId;

    @Schema(description = "组织名称")
    private String orgName;

    @Schema(description = "所属月份（1-12，趋势接口逐月返回时区分用）")
    private int month;

    @Schema(description = "超前完成数")
    private long ahead;

    @Schema(description = "正常推进数")
    private long normal;

    @Schema(description = "延期数")
    private long delayed;

    @Schema(description = "计数合计（ahead + normal + delayed）")
    private long total;

    @Schema(description = "当年已下发到该学院的指标数（status=DISTRIBUTED，灰柱显示「下发 n 项」）")
    private long dispatchedCount;

    @Schema(description = "当年未下发到该学院的指标数（status=DRAFT/PENDING）")
    private long undispatchedCount;

    @Schema(description = "已填报标记：false=当月无已提交/审批中/已审批月报（灰柱督办），true=已填报"
            + "（含审批中，审批状态看 monthState）")
    private boolean reported;

    @Schema(description = "当月快照状态（2026-11-18 拍板）：APPROVED=该月月报已审批通过（等级计数有效）；"
            + "IN_REVIEW=该月已填报但审批未通过（等级计数全 0，前端置灰标「审批中」）；"
            + "NOT_REPORTED=该月未填报（灰柱督办）")
    private MonthState monthState;
}
