package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件历史流水。状态/指挥人未变化的维度对应字段为 null。
 */
public record IncidentEvent(
        Long id,
        long incidentId,
        String eventType,
        String actorId,
        String fromStatus,
        String toStatus,
        String fromCommanderId,
        String toCommanderId,
        String detail,
        Instant createdAt) {
}
