package com.example.starter.incident.plan;

/**
 * 方案任务状态：PENDING 待执行 / IN_PROGRESS 执行中 / COMPLETED 已完成。
 * 仅允许 PENDING→IN_PROGRESS→COMPLETED；COMPLETED 的状态与完成事实不可回退。
 */
public enum PlanTaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}
