package com.example.starter.firmware.domain;

/**
 * 多跳回退计划状态：
 * ACTIVE 波次执行中；PAUSED 某跳失败率达到阈值自动暂停（可人工恢复生成新round）；
 * COMPLETED 全部设备已到达目标版本（终态）；CANCELLED 已人工取消（终态）。
 */
public enum RollbackPlanStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED
}
