package com.example.starter.firmware.domain;

/**
 * 双分区试启动任务状态：PENDING 待试启动；TRIAL 试启动中（未切换当前版本）；
 * COMMITTED 健康确认通过、已切换版本与分区（终态）；ROLLED_BACK 已回滚（终态）；
 * CANCELLED 已取消（终态）。
 */
public enum DualBootTaskStatus {
    PENDING,
    TRIAL,
    COMMITTED,
    ROLLED_BACK,
    CANCELLED
}