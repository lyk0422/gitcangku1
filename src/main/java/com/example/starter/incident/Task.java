package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * 任务从属于某一事件（域内）；可携带若干前置阻塞事件（incident_task_blockers）。
 * status=OPEN 未完成 / DONE 已完成；completedAt 仅 DONE 有值。
 */
public record Task(
        long id,
        long incidentId,
        String taskKey,
        String title,
        TaskStatus status,
        String actor,
        Instant createdAt,
        Instant completedAt) {
}
