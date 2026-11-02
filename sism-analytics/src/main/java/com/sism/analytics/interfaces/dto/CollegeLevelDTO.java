package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CollegeLevelDTO - 单个学院的鉴定等级计数。
 * Matches frontend dashboard college-levels contract (2026-10-31 拍板口径)
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

    @Schema(description = "超前完成数")
    private long ahead;

    @Schema(description = "正常推进数")
    private long normal;

    @Schema(description = "延期数")
    private long delayed;

    @Schema(description = "计数合计（ahead + normal + delayed）")
    private long total;
}
