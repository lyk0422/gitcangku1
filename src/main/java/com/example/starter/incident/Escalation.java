package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件升级记录实体，对应 incident_escalations 表。
 * 两域都会记录升级历史；但只有 REAL 域升级会触发对外通知（写入 notification_outbox），
 * DRILL 域升级不产生任何真实域副作用。
 */
public record Escalation(
        long id,
        long incidentId,
        String escalateTo,
        String reason,
        String actor,
        Instant createdAt) {
}
