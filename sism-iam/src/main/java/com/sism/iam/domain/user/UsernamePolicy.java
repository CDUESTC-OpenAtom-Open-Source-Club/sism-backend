package com.sism.iam.domain.user;

public final class UsernamePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 20;
    public static final String REGEX = "^[A-Za-z0-9_]{3,20}$";

    private UsernamePolicy() {
    }

    public static void validate(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("请输入用户名");
        }
        if (username.length() < MIN_LENGTH || username.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("用户名长度需为3-20个字符");
        }
        if (!username.matches(REGEX)) {
            throw new IllegalArgumentException("用户名只能包含字母、数字和下划线");
        }
    }
}
