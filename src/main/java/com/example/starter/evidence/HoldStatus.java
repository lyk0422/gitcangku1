package com.example.starter.evidence;

/**
 * 冻结记录状态。
 * ACTIVE 未解除；RELEASED 已由不同于创建人的保管人解除。
 * 记录状态不随到期改写：是否仍“有效”需结合 UTC 窗口左闭右开判定（当前时刻 &lt; expiresAt）。
 */
public enum HoldStatus {
    ACTIVE,
    RELEASED
}
