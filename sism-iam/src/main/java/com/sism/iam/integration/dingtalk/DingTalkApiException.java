package com.sism.iam.integration.dingtalk;

public class DingTalkApiException extends RuntimeException {

    public DingTalkApiException(String message) {
        super(message);
    }

    public DingTalkApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
