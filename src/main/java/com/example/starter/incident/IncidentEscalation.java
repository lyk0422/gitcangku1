package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件升级记录实体，对应 incident_escalations 表。
 * 演练域升级仅落本表，不触发真实通知；只有真实域升级才写通知副作用记录。
 */
public record IncidentEscalation(
        long id,
        long incidentId,
        String fromSeverity,
        String toSeverity,
        String reason,
        String actor,
        Instant createdAt) {
}
