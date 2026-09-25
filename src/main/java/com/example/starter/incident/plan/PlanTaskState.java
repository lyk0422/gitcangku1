package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案任务运行时执行状态：跟随事件当前活动版本，按稳定 taskId 延续。
 * 发布新版本时 PENDING 行同步计划负责人，IN_PROGRESS/COMPLETED 行的事实不可回退；
 * 被移除任务的行随发布删除（COMPLETED/IN_PROGRESS 任务受合并校验保护不可移除）。
 */
public record PlanTaskState(long id, long incidentId, String taskId, PlanTaskStatus status,
                            String assignee, String startedBy, Instant startedAt,
                            String completedBy, Instant completedAt,
                            Instant createdAt, Instant updatedAt) {
}
