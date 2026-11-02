package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CollegeLevelsResponseDTO - 学院指标完成情况（单月）响应。
 * Matches frontend dashboard college-levels contract (2026-10-31 拍板口径)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "学院指标完成情况（单月）")
public class CollegeLevelsResponseDTO {

    @Schema(description = "年份")
    private int year;

    @Schema(description = "月份")
    private int month;

    @Schema(description = "当月有已鉴定上报记录的学院列表（未上报学院不返回）")
    private List<CollegeLevelDTO> colleges;
}
