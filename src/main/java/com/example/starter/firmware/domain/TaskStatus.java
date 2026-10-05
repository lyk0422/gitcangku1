package com.example.starter.firmware.domain;

/**
 * 投放任务状态。普通灰度任务：PENDING 待回执；SUCCESS 成功；FAILED 失败；CANCELLED 已取消。
 * 双分区试启动任务：PENDING 待试启动；TRIAL 试启动中（未切换版本）；COMMITTED 健康确认通过并已切换；
 * ROLLED_BACK 已回滚（保留旧分区旧版本）；CANCELLED 已取消。
 */
public enum TaskStatus {
    PENDING,
    SUCCESS,
    FAILED,
    CANCELLED,
    TRIAL,
    COMMITTED,
    ROLLED_BACK
}