package com.example.starter.observation;

/**
 * 脱敏版本公开展示资格：ACTIVE 有效（公开查询可见）；REVOKED 已撤销（仅证据权限可读）。
 * 撤销不删除版本行、脱敏内容、理由与撤销时刻。
 */
public enum RedactionStatus {
    ACTIVE,
    REVOKED;

    public static RedactionStatus fromValue(String value) {
        return value == null ? null : RedactionStatus.valueOf(value.trim().toUpperCase());
    }
}
