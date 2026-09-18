package com.sism.iam.application.service;

import com.sism.iam.application.service.UserNotificationService;
import com.sism.iam.domain.notification.UserNotification;
import com.sism.iam.domain.notification.UserNotificationRepository;
import com.sism.iam.domain.user.UserRepository;
import com.sism.shared.domain.notification.NotificationProvider.AlertNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 三档进度等级（AHEAD/NORMAL/DELAYED）通知文案回归测试。
 *
 * <p>背景：手动进度等级链路（A1 修复，2026-09-18）之前复用告警文案，
 * 三档会显示「触发了提示级别告警，实际进度 0%…」，语义不通。
 */
@ExtendWith(MockitoExtension.class)
class UserNotificationServiceTest {

    @Mock
    private UserNotificationRepository userNotificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private UserNotificationService service;

    private void stubSave() {
        when(userNotificationRepository.save(any(UserNotification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void delayedProgressLevelShouldUseDedicatedCopy() {
        stubSave();

        AlertNotification result = service.createAlertNotification(
                401L, 9001L, 35L, 5L, 2039L, "完成重点提案办理与反馈督办",
                "DELAYED", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        org.mockito.Mockito.verify(userNotificationRepository).save(captor.capture());
        UserNotification saved = captor.getValue();

        assertEquals("进度等级调整通知（延期）", saved.getTitle());
        assertEquals("指标「完成重点提案办理与反馈督办」的进度等级被上级调整为「延期」，请知悉。", saved.getContent());
        assertEquals(result.alertId(), 5L);
    }

    @Test
    void aheadAndNormalProgressLevelsShouldUseDedicatedCopy() {
        stubSave();

        service.createAlertNotification(401L, 9001L, 35L, 6L, 2039L, "指标甲",
                "AHEAD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        service.createAlertNotification(401L, 9001L, 35L, 7L, 2039L, "指标乙",
                "NORMAL", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        org.mockito.Mockito.verify(userNotificationRepository, org.mockito.Mockito.times(2))
                .save(captor.capture());
        assertEquals("进度等级调整通知（超前完成）", captor.getAllValues().get(0).getTitle());
        assertEquals("指标「指标甲」的进度等级被上级调整为「超前完成」，请知悉。",
                captor.getAllValues().get(0).getContent());
        assertEquals("进度等级调整通知（正常）", captor.getAllValues().get(1).getTitle());
    }

    @Test
    void legacyWarningSeverityShouldKeepAlertCopy() {
        stubSave();

        service.createAlertNotification(401L, 9001L, 35L, 8L, 2039L, "指标丙",
                "WARNING", BigDecimal.valueOf(45.5), BigDecimal.valueOf(80), BigDecimal.valueOf(-34.5));

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        org.mockito.Mockito.verify(userNotificationRepository).save(captor.capture());
        assertEquals("指标告警通知（警告）", captor.getValue().getTitle());
        assertTrue(captor.getValue().getContent().contains("触发了警告级别告警"));
        assertTrue(captor.getValue().getContent().contains("实际进度 45.5%"));
    }

    @Test
    void unknownSeverityShouldFallBackToInfoAlertCopy() {
        stubSave();

        service.createAlertNotification(401L, 9001L, 35L, 9L, 2039L, "指标丁",
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        org.mockito.Mockito.verify(userNotificationRepository).save(captor.capture());
        assertEquals("指标告警通知（提示）", captor.getValue().getTitle());
    }
}
