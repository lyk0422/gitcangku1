package com.example.starter.firmware.domain;

/**
 * 双分区试启动回滚原因：EXPIRED 达到 60 秒仍未收到健康确认；FAILED 设备上报失败。
 */
public enum RollbackReason {
    EXPIRED,
    FAILED
}
