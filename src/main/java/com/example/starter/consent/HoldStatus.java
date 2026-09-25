package com.example.starter.consent;

/**
 * 保留冻结状态：ACTIVE 生效；RELEASED 已解除。
 * 到期不落库，由 expires_at 与当前 UTC 时刻（可注入时钟）判定，派生为 EXPIRED 展示。
 */
public enum HoldStatus {
    ACTIVE,
    RELEASED
}
