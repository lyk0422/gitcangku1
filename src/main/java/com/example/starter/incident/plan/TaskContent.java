package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案任务内容值对象：以稳定 taskId 标识，参与三方合并比较的全部业务字段。
 * completedBy/completedAt 仅 COMPLETED 有值，否则为空（完成事实）。
 */
public record TaskContent(
        String taskId,
        String incidentKey,
        String groupCode,
        String title,
        String assignee,
        PlanTaskStatus status,
        String completedBy,
        Instant completedAt) {
}
