package com.example.starter.observation;

/**
 * 脱敏规则版本状态：ACTIVE 登记可用；REVOKED 已撤销（不可逆，不可再用于新脱敏）。
 */
public enum RuleStatus {
    ACTIVE,
    REVOKED;

    public static RuleStatus fromValue(String value) {
        return value == null ? null : RuleStatus.valueOf(value.trim().toUpperCase());
    }
}
