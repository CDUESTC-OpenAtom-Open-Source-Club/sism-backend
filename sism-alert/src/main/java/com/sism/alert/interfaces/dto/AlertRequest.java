package com.sism.alert.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * AlertRequest - 创建预警请求DTO
 */
@Data
@Schema(description = "创建预警请求")
public class AlertRequest {

    @Schema(description = "关联指标ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "指标ID不能为空")
    @Positive(message = "指标ID必须为正数")
    private Long indicatorId;

    @Schema(description = "预警规则ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "规则ID不能为空")
    @Positive(message = "规则ID必须为正数")
    private Long ruleId;

    @Schema(description = "预警窗口ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "窗口ID不能为空")
    @Positive(message = "窗口ID必须为正数")
    private Long windowId;

    @Schema(description = "严重程度（进度等级三档 AHEAD/NORMAL/DELAYED；旧档 CRITICAL/WARNING/INFO，兼容 MAJOR/MINOR 别名）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "WARNING")
    @NotBlank(message = "严重程度不能为空")
    @Pattern(regexp = "^(?i)(INFO|WARNING|CRITICAL|MAJOR|MINOR|AHEAD|NORMAL|DELAYED)$",
            message = "严重程度必须是 AHEAD/NORMAL/DELAYED 或旧档 INFO/WARNING/CRITICAL、MAJOR/MINOR")
    private String severity;

    @Schema(description = "实际完成百分比", requiredMode = Schema.RequiredMode.REQUIRED, example = "45.50")
    @NotNull(message = "实际完成百分比不能为空")
    private BigDecimal actualPercent;

    @Schema(description = "预期完成百分比", requiredMode = Schema.RequiredMode.REQUIRED, example = "80.00")
    @NotNull(message = "预期完成百分比不能为空")
    private BigDecimal expectedPercent;

    @Schema(description = "差距百分比", requiredMode = Schema.RequiredMode.REQUIRED, example = "-34.50")
    @NotNull(message = "差距百分比不能为空")
    private BigDecimal gapPercent;

    @Schema(description = "详情JSON", example = "{}")
    private String detailJson;
}
