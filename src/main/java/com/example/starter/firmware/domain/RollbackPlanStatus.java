package com.example.starter.firmware.domain;

/**
 * 回退计划状态：ACTIVE 波次执行中；PAUSED 某跳失败率自动暂停（可人工恢复）；
 * COMPLETED 全部设备到达目标版本（终态）；CANCELLED 已取消（终态）。
 */
public enum RollbackPlanStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED
}
