package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CollegeLevelTrendResponseDTO - 学院指标完成情况（月度趋势）响应。
 * Matches frontend dashboard college-levels-trend contract (2026-11-17 拍板口径)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "学院指标完成情况（月度趋势）")
public class CollegeLevelTrendResponseDTO {

    @Schema(description = "年份")
    private int year;

    @Schema(description = "1-12 月全量月份列表（升序；每月含当年有指标/当月已上报的学院，未填报 reported=false）")
    private List<CollegeLevelMonthDTO> months;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单月学院计数")
    public static class CollegeLevelMonthDTO {

        @Schema(description = "月份")
        private int month;

        @Schema(description = "学院列表（含未填报学院，reported=false 灰柱督办）")
        private List<CollegeLevelDTO> colleges;
    }
}
