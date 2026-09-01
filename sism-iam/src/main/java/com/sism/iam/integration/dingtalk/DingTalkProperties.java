package com.sism.iam.integration.dingtalk;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.dingtalk")
public class DingTalkProperties {

    /**
     * Master switch; when false every DingTalk call degrades to a no-op so local
     * development and tests never depend on external connectivity.
     */
    private boolean enabled = false;

    private String appKey = "";

    private String appSecret = "";

    /**
     * Public base URL of the H5 microapp, used to build todo detail deep links,
     * e.g. https://sism.blackevil.cn
     */
    private String h5BaseUrl = "";

    /**
     * 企业 corpId，前端 H5 免登 JSAPI 需要它来申请免登码。
     */
    private String corpId = "";

    /**
     * 待办卡片类型 ID（configs/types 接口返回的 id）。配置后待办以自定义
     * 双按钮卡片渲染：原生「完成待办」+ 自定义「查看详情」跳转按钮。
     */
    private String todoCardTypeKey = "";

    /**
     * 企业内部应用 AgentId（h5_app_open AppLink 的 appId 参数）。
     * 未配置时回落到 AppKey（部分场景两者一致，不一致时控制台基础信息页可查）。
     */
    private String agentId = "";

    private String oapiBaseUrl = "https://oapi.dingtalk.com";

    private String apiBaseUrl = "https://api.dingtalk.com";

    private int timeoutSeconds = 15;

    public boolean isConfigured() {
        return enabled
                && appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /**
     * AppLink appId 缺省回落到 AppKey。
     */
    public String resolveAppLinkAppId() {
        if (agentId != null && !agentId.isBlank()) {
            return agentId;
        }
        return appKey;
    }
}
