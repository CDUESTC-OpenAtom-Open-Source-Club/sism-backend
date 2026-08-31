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
     * 机器人编码，用于发送自定义审批卡片；企业内部应用默认与应用 AppKey 相同。
     */
    private String robotCode = "";

    private String oapiBaseUrl = "https://oapi.dingtalk.com";

    private String apiBaseUrl = "https://api.dingtalk.com";

    private int timeoutSeconds = 15;

    public boolean isConfigured() {
        return enabled
                && appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /**
     * 机器人编码缺省回落到 AppKey（企业内部应用机器人编码与 AppKey 一致）。
     */
    public String resolveRobotCode() {
        if (robotCode != null && !robotCode.isBlank()) {
            return robotCode;
        }
        return appKey;
    }
}
