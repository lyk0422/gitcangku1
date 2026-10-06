package com.example.starter.firmware.domain;

/**
 * 双分区任务回滚原因：EXPIRED 试启动后60秒内未收到健康确认；FAILED 设备上报试启动失败。
 */
public enum RollbackReason {
    EXPIRED,
    FAILED
}
