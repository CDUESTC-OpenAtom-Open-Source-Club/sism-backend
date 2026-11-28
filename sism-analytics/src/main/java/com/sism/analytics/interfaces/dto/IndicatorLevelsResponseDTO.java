package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * IndicatorLevelsResponseDTO - 行级鉴定明细（看板下钻明细面板数据源，2026-11-27 新增）。
 *
 * <p>口径与看板聚合一致：只有 APPROVED 月报计入，鉴定优先自评
 * （level = COALESCE(appraisal_level, self_rating)）。行主体 = 指标 target 组织
 * （责任单位口径），orgId=null 表示全量视角未指定组织。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "行级鉴定明细（按责任单位与月份）")
public class IndicatorLevelsResponseDTO {

    @Schema(description = "年份")
    private int year;

    @Schema(description = "月份（1-12；null 表示年度模式，返回全年各月）")
    private Integer month;

    @Schema(description = "责任单位组织 ID（指标 target 组织；null = 全量视角未指定组织）")
    private Long orgId;

    @Schema(description = "鉴定明细行（按月份、指标ID升序，同指标同月多轮上报取最后一条）")
    private List<IndicatorLevelItemDTO> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单条鉴定明细")
    public static class IndicatorLevelItemDTO {

        @Schema(description = "指标ID")
        private long indicatorId;

        @Schema(description = "指标描述")
        private String indicatorName;

        @Schema(description = "上报月份（1-12）")
        private int month;

        @Schema(description = "自评等级 AHEAD/NORMAL/DELAYED（可能为空）")
        private String selfRating;

        @Schema(description = "上级鉴定等级 AHEAD/NORMAL/DELAYED（可能为空）")
        private String appraisalLevel;

        @Schema(description = "有效等级（鉴定优先自评：COALESCE(appraisal_level, self_rating)，可能为空）")
        private String level;
    }
}
