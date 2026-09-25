package com.example.starter.firmware.domain;

/**
 * 投放任务状态：PENDING 待回执；SUCCESS 成功；FAILED 失败；CANCELLED 已取消；
 * RELEASE_FROZEN 命中冻结令范围，在冻结开始时由 PENDING 转入，撤销冻结令也不自动复活。
 */
public enum TaskStatus {
    PENDING,
    SUCCESS,
    FAILED,
    CANCELLED,
    RELEASE_FROZEN
}
