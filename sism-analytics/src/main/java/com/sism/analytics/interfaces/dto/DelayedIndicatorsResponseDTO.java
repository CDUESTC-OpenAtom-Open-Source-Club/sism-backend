package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DelayedIndicatorsResponseDTO - 滞后任务清单（单月）响应。
 *
 * <p>2026-11-17 拍板：看板「滞后任务响应清单」的判定源切换为月报鉴定等级
 * （等级制填报后 indicator.progress 恒 0，旧的 progress&lt;50 判定已死），
 * 只取已提交/已审批月报、appraisal_level 优先 self_rating 兜底的 DELAYED 明细。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "滞后任务清单（单月）")
public class DelayedIndicatorsResponseDTO {

    @Schema(description = "年份")
    private int year;

    @Schema(description = "月份")
    private int month;

    @Schema(description = "滞后指标明细（按学院名+指标ID排序，指标ID去重）")
    private List<DelayedIndicatorDTO> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单条滞后指标")
    public static class DelayedIndicatorDTO {

        @Schema(description = "指标ID（催办接口入参）")
        private long indicatorId;

        @Schema(description = "指标描述")
        private String indicatorName;

        @Schema(description = "责任学院组织ID")
        private long orgId;

        @Schema(description = "责任学院名称")
        private String orgName;

        @Schema(description = "来源职能部门名称（可能为空）")
        private String ownerOrgName;

        @Schema(description = "自评等级 AHEAD/NORMAL/DELAYED（可能为空）")
        private String selfRating;

        @Schema(description = "上级鉴定等级 AHEAD/NORMAL/DELAYED（可能为空）")
        private String appraisalLevel;
    }
}
