package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LaggingTaskDTO - 「TOP 滞后任务响应清单」行。
 *
 * <p>数据来源：plan_report_indicator.appraisal_level = 'DELAYED' 的最近一次鉴定，
 * 责任主体取指标目标组织（indicator.target_org_id）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "滞后任务（上级鉴定为 DELAYED 的指标）")
public class LaggingTaskDTO {

    @Schema(description = "指标 ID（前端 REF_ID）")
    private long indicatorId;

    @Schema(description = "指标名称")
    private String indicatorName;

    @Schema(description = "责任主体组织 ID")
    private Long orgId;

    @Schema(description = "责任主体组织名称")
    private String orgName;

    @Schema(description = "最近一次鉴定所在月份，格式 yyyyMM")
    private String reportMonth;
}
