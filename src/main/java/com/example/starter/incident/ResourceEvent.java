package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源租约证据实体，对应 resource_assignment_events 表。
 * 只追加、不改写：后续任务修订或事件合并不改写原资源使用证据。
 * oldVersion 在 ASSIGN 时为空；newVersion 在 EXPIRE/EXECUTE_DENIED/RELEASE 时为空。
 */
public record ResourceEvent(
        long id,
        long assignmentId,
        long incidentId,
        long taskId,
        String resourceKey,
        ResourceEventType eventType,
        Integer oldVersion,
        Integer newVersion,
        String actor,
        Instant occurredAt,
        String detail) {
}
