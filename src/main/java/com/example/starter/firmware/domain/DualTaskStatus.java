package com.example.starter.firmware.domain;

/**
 * 双分区试启动任务状态：PENDING 待试启动；TRIAL 试启动中（60秒内待健康确认）；
 * COMMITTED 已正式切换（终态）；ROLLED_BACK 已回滚（终态）。
 */
public enum DualTaskStatus {
    PENDING,
    TRIAL,
    COMMITTED,
    ROLLED_BACK
}
