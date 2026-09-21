package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置记录。actionKey 在事件内唯一；occurredAt 为调用方提供的处置发生 UTC 时间。
 */
public record IncidentAction(
        Long id,
        long incidentId,
        String actionKey,
        String actorId,
        Instant occurredAt,
        String actionType,
        String description,
        Instant createdAt) {
}
