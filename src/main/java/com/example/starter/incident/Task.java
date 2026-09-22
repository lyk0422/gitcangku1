package com.example.starter.incident;

import java.time.Instant;

/**
 * 分组处置任务实体，对应 incident_tasks 表。
 * status 为 OPEN/DONE/CANCELLED；completedAt 仅 DONE 有值，cancelledAt 仅 CANCELLED 有值，否则为 null。
 * 时间均为 UTC。
 */
public record Task(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskStatus status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant cancelledAt) {
}
