package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件实体。commanderId 为 null 表示尚未接管；pendingCommanderId 为 null 表示无待接受交接。
 * 时间字段均为 UTC 时刻。
 */
public record Incident(
        Long id,
        String incidentKey,
        Severity severity,
        String summary,
        String reporter,
        IncidentStatus status,
        String commanderId,
        String pendingCommanderId,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
