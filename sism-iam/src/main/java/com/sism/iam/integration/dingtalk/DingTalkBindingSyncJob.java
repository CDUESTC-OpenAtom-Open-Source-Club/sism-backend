package com.sism.iam.integration.dingtalk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 钉钉绑定定时同步：为已配置手机号的活跃账号补建/刷新钉钉绑定，
 * 保证待办推送与免登在新账号开通后最多一个同步周期内可用。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkBindingSyncJob {

    private final DingTalkUserBindingService bindingService;

    @Scheduled(
            initialDelayString = "${app.dingtalk.binding-sync-initial-delay-ms:60000}",
            fixedDelayString = "${app.dingtalk.binding-sync-interval-ms:3600000}")
    public void syncBindings() {
        try {
            bindingService.syncAllBindings();
        } catch (Exception ex) {
            log.warn("DingTalk binding sync job failed: {}", ex.getMessage());
        }
    }
}
