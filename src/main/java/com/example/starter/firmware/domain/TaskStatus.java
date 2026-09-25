package com.example.starter.firmware.domain;

/**
 * 投放任务状态：PENDING 待开始；IN_PROGRESS 已开始进行中；SUCCESS 成功；FAILED 失败；CANCELLED 已取消。
 */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    CANCELLED
}
