package com.sism.alert.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "手动预警等级设置请求")
public class ManualAlertLevelRequest {

    @Schema(description = "预警等级。为空或 NONE 表示取消预警；INFO/WARNING/CRITICAL 分别表示一般/严重/重大滞后",
            example = "INFO")
    @Pattern(regexp = "^(?i)(NONE|INFO|WARNING|CRITICAL|MAJOR|MINOR)?$",
            message = "预警等级必须是 NONE/INFO/WARNING/CRITICAL")
    private String severity;
}
