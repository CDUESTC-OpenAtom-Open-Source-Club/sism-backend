package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CollegeLevelTrendResponseDTO - 学院指标完成情况（月度趋势）响应。
 * Matches frontend dashboard college-levels-trend contract (2026-10-31 拍板口径)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "学院指标完成情况（月度趋势）")
public class CollegeLevelTrendResponseDTO {

    @Schema(description = "年份")
    private int year;

    @Schema(description = "有数据的月份列表（升序，仅含有计数>0的月份）")
    private List<CollegeLevelMonthDTO> months;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单月学院计数")
    public static class CollegeLevelMonthDTO {

        @Schema(description = "月份")
        private int month;

        @Schema(description = "当月有已鉴定上报记录的学院列表")
        private List<CollegeLevelDTO> colleges;
    }
}
