package com.example.starter.firmware.domain;

/**
 * 设备状态：NORMAL 正常；QUARANTINED 已隔离（不得拉取新任务，回执被拒且不计失败率样本）。
 */
public enum DeviceStatus {
    NORMAL,
    QUARANTINED
}
