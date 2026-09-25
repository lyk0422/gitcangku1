package com.example.starter.consent;

/**
 * 保留冻结状态：ACTIVE 生效中；RELEASED 已人工解除。
 *
 * <p>到期不改变行状态，是否到期由 {@code expires_at} 与可注入时钟判定：
 * ACTIVE 且当前时刻未到 {@code expires_at} 才视为生效冻结。
 */
public enum HoldStatus {
    ACTIVE,
    RELEASED
}
