package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AnnualLevelSummaryDTO - 「任务完成简报」年度口径汇总。
 *
 * <p>同一指标一年内可能多次上报，只取最近一次鉴定计入；completionRate =
 * (ahead + normal) / total * 100，四舍五入。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "年度鉴定等级汇总（简报口径）")
public class AnnualLevelSummaryDTO {

    @Schema(description = "超前完成数")
    private long ahead;

    @Schema(description = "正常推进数")
    private long normal;

    @Schema(description = "滞后数（上级鉴定 DELAYED）")
    private long delayed;

    @Schema(description = "指标总数（去重后的指标数）")
    private long total;

    @Schema(description = "总体完成率百分比（0-100，保留整数）")
    private int completionRate;
}
