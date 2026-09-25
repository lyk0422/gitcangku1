package com.example.starter.consent;

/**
 * 数据接收方状态：ENABLED 可用 / DISABLED 已整体禁用。只允许从 ENABLED 变为 DISABLED。
 */
public enum RecipientStatus {
    ENABLED,
    DISABLED
}
