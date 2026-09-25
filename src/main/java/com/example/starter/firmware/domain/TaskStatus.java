package com.example.starter.firmware.domain;

/**
 * 投放任务状态：
 * PENDING 已拉取未开始（隔离时转 CANCELLED）；
 * STARTED 设备已开始刷写、进行中（隔离时保持进行中，但回执被门禁拒绝）；
 * SUCCESS 成功；FAILED 失败；CANCELLED 已取消。
 */
public enum TaskStatus {
    PENDING,
    STARTED,
    SUCCESS,
    FAILED,
    CANCELLED
}
