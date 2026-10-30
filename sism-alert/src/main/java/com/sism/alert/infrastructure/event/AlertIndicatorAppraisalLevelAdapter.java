package com.sism.alert.infrastructure.event;

import com.sism.alert.application.AlertApplicationService;
import com.sism.shared.domain.indicator.IndicatorAppraisalLevelPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把审批链上的审核进度同步到 Alert 手动等级（2026-10-28）。
 * 尽力而为：失败只记日志，不回滚审批数据。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlertIndicatorAppraisalLevelAdapter implements IndicatorAppraisalLevelPort {

    private final AlertApplicationService alertApplicationService;

    @Override
    public void syncManualLevel(Long indicatorId, String level, Long handledBy) {
        if (indicatorId == null || indicatorId <= 0) {
            return;
        }
        try {
            alertApplicationService.setManualAlertLevel(indicatorId, level, handledBy);
        } catch (Exception ex) {
            log.warn("Failed to sync appraisal level to alert: indicatorId={}, level={}, error={}",
                    indicatorId, level, ex.getMessage());
        }
    }
}
