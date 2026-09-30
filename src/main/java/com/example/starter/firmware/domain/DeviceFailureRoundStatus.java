package com.example.starter.firmware.domain;

/**
 * 设备-版本连续失败轮次状态：
 * COUNTING 连续失败计数中（已发生1次失败，未达隔离阈值）；
 * QUARANTINED 连续两次失败已隔离，设备拉取不到该版本新任务；
 * RELEASED 已由不同于隔离确认人的审核人解除，可开启新一轮投放，历史失败保留；
 * RECOVERED 设备已成功升级到该版本，本轮连续失败计数自然终结，历史保留。
 */
public enum DeviceFailureRoundStatus {
    COUNTING,
    QUARANTINED,
    RELEASED,
    RECOVERED
}
