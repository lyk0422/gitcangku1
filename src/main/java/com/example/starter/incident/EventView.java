package com.example.starter.incident;

import java.time.Instant;

/**
 * 历史流水视图。
 */
public record EventView(
        String eventType,
        String actorId,
        String fromStatus,
        String toStatus,
        String fromCommanderId,
        String toCommanderId,
        String detail,
        Instant createdAt) {

    public static EventView of(IncidentEvent event) {
        return new EventView(
                event.eventType(),
                event.actorId(),
                event.fromStatus(),
                event.toStatus(),
                event.fromCommanderId(),
                event.toCommanderId(),
                event.detail(),
                event.createdAt());
    }
}
