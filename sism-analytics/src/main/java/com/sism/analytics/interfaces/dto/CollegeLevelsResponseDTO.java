package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CollegeLevelsResponseDTO - 学院指标完成情况（单月）响应。
 * Matches frontend dashboard college-levels contract (2026-11-17 拍板口径)
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

    @Schema(description = "学院列表（2026-11-17 口径：当年有指标的学院 ∪ 当月已上报学院；"
            + "未填报学院 reported=false 由前端画灰柱督办）")
    private List<CollegeLevelDTO> colleges;
}
