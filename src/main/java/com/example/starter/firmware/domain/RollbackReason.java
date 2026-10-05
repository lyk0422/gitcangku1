package com.example.starter.firmware.domain;

/**
 * 双分区试启动回滚原因：EXPIRED 试启动 60 秒内未收到健康确认；FAILED 设备回报失败。
 */
public enum RollbackReason {
    EXPIRED,
    FAILED
}