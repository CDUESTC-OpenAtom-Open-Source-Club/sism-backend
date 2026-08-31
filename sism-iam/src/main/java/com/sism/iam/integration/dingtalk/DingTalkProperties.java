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

    private String oapiBaseUrl = "https://oapi.dingtalk.com";

    private String apiBaseUrl = "https://api.dingtalk.com";

    private int timeoutSeconds = 15;

    public boolean isConfigured() {
        return enabled
                && appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }
}
