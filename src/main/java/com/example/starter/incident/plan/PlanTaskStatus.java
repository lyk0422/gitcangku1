package com.example.starter.incident.plan;

/**
 * 方案任务运行时执行状态（存于 plan_task_state，按事件 + 稳定 taskId 跟随活动版本）：
 * PENDING 待执行 / IN_PROGRESS 执行中 / COMPLETED 已完成（终态，完成事实不可回退）。
 */
public enum PlanTaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}
