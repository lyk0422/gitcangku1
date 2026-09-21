package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置记录视图。
 */
public record ActionView(
        String actionKey,
        String actorId,
        Instant occurredAt,
        String type,
        String description,
        Instant createdAt) {

    public static ActionView of(IncidentAction action) {
        return new ActionView(
                action.actionKey(),
                action.actorId(),
                action.occurredAt(),
                action.actionType(),
                action.description(),
                action.createdAt());
    }
}
