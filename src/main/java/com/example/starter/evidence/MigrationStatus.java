package com.example.starter.evidence;

/**
 * 介质迁移状态。
 * PENDING_VERIFY 待见证验证；PENDING_SWITCH 已验证待保管人切换；
 * SWITCHED 已切换（终态）；CANCELLED 已取消（终态，源副本不受影响）。
 */
public enum MigrationStatus {
    PENDING_VERIFY,
    PENDING_SWITCH,
    SWITCHED,
    CANCELLED
}
