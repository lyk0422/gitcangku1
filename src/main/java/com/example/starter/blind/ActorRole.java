package com.example.starter.blind;

/**
 * 操作者角色，由 X-Role 请求头提供；本题信任本地测试头。
 */
public enum ActorRole {
    /** 协调员：创建实验、登记、退组、关闭、提出揭盲申请。 */
    COORDINATOR,
    /** 审核员：批准揭盲申请。 */
    REVIEWER;

    public static ActorRole fromHeader(String value) {
        if (value == null) {
            throw ApiException.forbidden("missing X-Role header");
        }
        try {
            return ActorRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.forbidden("unknown role: " + value);
        }
    }
}
