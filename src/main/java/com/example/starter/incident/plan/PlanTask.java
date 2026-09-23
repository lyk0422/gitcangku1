package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案版本任务快照实体，对应 plan_tasks 表。
 * taskId 为稳定任务标识，版本内唯一，跨版本沿用同一 taskId 进行三方比较。
 */
public record PlanTask(
        long id,
        long versionId,
        String taskId,
        String incidentKey,
        String groupCode,
        String title,
        String assignee,
        PlanTaskStatus status,
        String completedBy,
        Instant completedAt) {

    /**
     * 提取参与三方合并比较的业务内容。
     */
    public TaskContent content() {
        return new TaskContent(taskId, incidentKey, groupCode, title, assignee, status,
                completedBy, completedAt);
    }
}
