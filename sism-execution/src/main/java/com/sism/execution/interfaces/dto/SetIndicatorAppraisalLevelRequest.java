package com.sism.execution.interfaces.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 审批链上的行内审核进度调整（2026-10-28）。
 * level 为空字符串/null 表示恢复未评定。
 */
@Data
public class SetIndicatorAppraisalLevelRequest {

    @Pattern(regexp = "^$|^(AHEAD|NORMAL|DELAYED)$",
            message = "审核进度只能是 AHEAD / NORMAL / DELAYED 或留空")
    private String level;
}
