package com.sism.alert.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "手动预警等级设置请求")
public class ManualAlertLevelRequest {

    @Schema(description = "进度等级。为空或 NONE 表示未评定；三档进度等级 AHEAD/NORMAL/DELAYED（超前/正常/延期）；"
            + "旧档 INFO/WARNING/CRITICAL 兼容历史数据，分别表示一般/严重/重大滞后",
            example = "DELAYED")
    @Pattern(regexp = "^(?i)(NONE|INFO|WARNING|CRITICAL|MAJOR|MINOR|AHEAD|NORMAL|DELAYED)?$",
            message = "进度等级必须是 NONE/AHEAD/NORMAL/DELAYED 或兼容旧档 INFO/WARNING/CRITICAL")
    private String severity;
}
