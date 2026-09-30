package com.example.starter.firmware.domain;

/**
 * 隔离轮次状态：
 * TRACKING 计数中（本轮尚未连续两次失败）；
 * QUARANTINED 已隔离，设备对该目标版本拉取返回 409；
 * RELEASED 已解除（终态，仅保留历史），解除时另开新一轮 TRACKING。
 */
public enum QuarantineStatus {
    TRACKING,
    QUARANTINED,
    RELEASED
}
