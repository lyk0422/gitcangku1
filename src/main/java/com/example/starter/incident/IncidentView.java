package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件视图。commanderId 为 null 表示未接管；pendingCommanderId 为 null 表示无待交接。
 */
public record IncidentView(
        String incidentKey,
        Severity severity,
        String summary,
        String reporter,
        IncidentStatus status,
        String commanderId,
        String pendingCommanderId,
        Instant createdAt,
        Instant updatedAt) {

    public static IncidentView of(Incident incident) {
        return new IncidentView(
                incident.incidentKey(),
                incident.severity(),
                incident.summary(),
                incident.reporter(),
                incident.status(),
                incident.commanderId(),
                incident.pendingCommanderId(),
                incident.createdAt(),
                incident.updatedAt());
    }
}
