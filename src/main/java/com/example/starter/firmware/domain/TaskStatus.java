package com.example.starter.firmware.domain;

/**
 * 投放任务状态：PENDING 待回执；SUCCESS 成功；FAILED 失败；CANCELLED 已取消；
 * RELEASE_FROZEN 被生效中的冻结令冻结，解冻后回到 PENDING。
 */
public enum TaskStatus {
    PENDING,
    SUCCESS,
    FAILED,
    CANCELLED,
    RELEASE_FROZEN
}
