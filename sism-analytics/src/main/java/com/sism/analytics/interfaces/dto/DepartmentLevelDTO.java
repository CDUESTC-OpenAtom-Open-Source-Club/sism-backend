package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DepartmentLevelDTO - 单个职能部门「截至某月的最近一次鉴定」等级计数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "职能部门指标鉴定等级计数")
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
}
