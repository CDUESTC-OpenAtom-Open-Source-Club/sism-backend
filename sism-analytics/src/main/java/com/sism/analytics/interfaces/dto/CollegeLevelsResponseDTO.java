package com.sism.analytics.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CollegeLevelsResponseDTO - 学院指标完成情况（单月）响应。
 * Matches frontend dashboard college-levels contract
 * (2026-11-18 拍板口径：按月快照，只有 APPROVED 月报计入鉴定值，审批中置灰)
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

    @Schema(description = "学院列表（当年有指标的学院 ∪ 当月已上报学院；"
            + "未填报学院 reported=false/monthState=NOT_REPORTED 由前端画灰柱督办，"
            + "审批中学院 monthState=IN_REVIEW 等级计数全 0 置灰标「审批中」）")
    private List<CollegeLevelDTO> colleges;
}
