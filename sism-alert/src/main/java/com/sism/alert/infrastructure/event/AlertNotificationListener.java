package com.sism.alert.infrastructure.event;

import com.sism.alert.domain.Alert;
import com.sism.alert.domain.event.AlertCreatedEvent;
import com.sism.alert.domain.event.AlertResolvedEvent;
import com.sism.alert.domain.event.AlertTriggeredEvent;
import com.sism.shared.domain.notification.NotificationProvider;
import com.sism.alert.domain.repository.AlertRepository;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.repository.IndicatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class AlertNotificationListener {

    private final AlertRepository alertRepository;
    private final IndicatorRepository indicatorRepository;
    private final UserRepository userRepository;
    private final NotificationProvider notificationProvider;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAlertCreated(AlertCreatedEvent event) {
        if (event == null) {
            return;
        }
        notifyAlertRecipients(event.alertId(), event.severity());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAlertTriggered(AlertTriggeredEvent event) {
        if (event == null) {
            return;
        }

        notifyAlertRecipients(event.alertId(), event.severity());
    }

    /** H2（2026-09-19）：告警解决（等级调整/审批流转）后，关闭该告警历史推送的未读通知。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAlertResolved(AlertResolvedEvent event) {
        if (event == null || event.alertId() == null) {
            return;
        }
        try {
            notificationProvider.deleteAlertNotifications(event.alertId());
        } catch (Exception ex) {
            log.error("Failed to delete resolved alert notifications: alertId={}, error={}",
                    event.alertId(), ex.getMessage(), ex);
        }
    }

    private void notifyAlertRecipients(Long alertId, String severity) {
        if (alertId == null) {
            return;
        }

        try {
            Alert alert = alertRepository.findById(alertId).orElse(null);
            if (alert == null) {
                log.warn("Alert not found for alert notification event: alertId={}", alertId);
                return;
            }

            Indicator indicator = indicatorRepository.findById(alert.getIndicatorId()).orElse(null);
            String indicatorName = indicator == null ? null : indicator.getIndicatorDesc();
            Long targetOrgId = indicator == null || indicator.getTargetOrg() == null
                    ? null : indicator.getTargetOrg().getId();
            Long ownerOrgId = indicator == null || indicator.getOwnerOrg() == null
                    ? null : indicator.getOwnerOrg().getId();

            if (targetOrgId == null) {
                log.warn("Alert indicator has no targetOrg, skipping notification: alertId={}, indicatorId={}",
                        alertId, alert.getIndicatorId());
                return;
            }

            List<User> recipients = userRepository.findByOrgId(targetOrgId).stream()
                    .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                    .toList();
            if (recipients.isEmpty()) {
                log.warn("No active users found in targetOrg for alert notification: alertId={}, targetOrgId={}",
                        alertId, targetOrgId);
                return;
            }

            for (User recipient : recipients) {
                notificationProvider.createAlertNotification(
                        recipient.getId(),
                        null,
                        ownerOrgId,
                        alert.getId(),
                        alert.getIndicatorId(),
                        indicatorName,
                        alert.getSeverity() == null ? null : alert.getSeverity().name(),
                        alert.getActualPercent(),
                        alert.getExpectedPercent(),
                        alert.getGapPercent()
                );
            }

            log.info("Alert notification sent: alertId={}, severity={}, recipientCount={}",
                    alertId, severity, recipients.size());
        } catch (Exception ex) {
            log.error("Failed to send alert notification: alertId={}, error={}",
                    alertId, ex.getMessage(), ex);
        }
    }
}
