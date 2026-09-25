package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案任务执行态（运行时叠加，按事件+稳定 taskId 定位，随版本切换保留）。
 * 无行表示 PENDING；IN_PROGRESS 记录执行负责人与开始事实；
 * COMPLETED 记录完成人与完成 UTC 时刻，完成事实不可回退。
 */
public record PlanTaskExecution(long id, String incidentKey, String taskId, PlanTaskStatus status,
                                String assignee, String startedBy, Instant startedAt,
                                String completedBy, Instant completedAt, Instant updatedAt) {
}
