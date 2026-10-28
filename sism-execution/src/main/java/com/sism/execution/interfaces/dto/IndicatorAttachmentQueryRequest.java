package com.sism.execution.interfaces.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量查询「当前登录人可见」的指标最新填报附件（2026-10-28）。
 *
 * 导出必须与页面同构：页面看不到的填报（未发起审批的草稿）不得出现在导出包里，
 * 因此由服务端按查看人组织过滤后返回，前端不再自行拼装附件。
 */
@Data
public class IndicatorAttachmentQueryRequest {

    @NotEmpty(message = "indicatorIds 不能为空")
    @Size(max = 2000, message = "单次最多查询 2000 个指标")
    private List<Long> indicatorIds;
}
