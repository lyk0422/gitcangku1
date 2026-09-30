package com.example.starter.firmware.domain;

/**
 * 设备版本失败轮次状态：
 * ACTIVE 计数中（已发生1次失败，仍可继续投放）；
 * QUARANTINED 连续2次失败已隔离（设备拉取返回409）；
 * RELEASED 已人工解除（轮次关闭，只开启新一轮投放，历史失败保留可查）。
 */
public enum RoundStatus {
    ACTIVE,
    QUARANTINED,
    RELEASED
}
