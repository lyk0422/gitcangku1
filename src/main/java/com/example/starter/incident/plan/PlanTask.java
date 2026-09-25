package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案版本任务（仅规划字段，执行状态见 PlanTaskExecution）。
 * taskId 为稳定任务 id，跨版本不变、版本内唯一，三方合并按其对齐；
 * assignee 为规划负责人，null 表示未指派。
 */
public record PlanTask(long id, long versionId, String taskId, String title, String assignee,
                       Instant createdAt, Instant updatedAt) {
}
