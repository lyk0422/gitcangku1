package com.example.starter.incident;

import java.time.Instant;

/**
 * 外部通报主实体，对应 incident_notifications 表；每次事件升级至多一条通报
 * （(incident_id)、(escalation_id) 均有唯一约束）。通报在升级时创建，
 * 确定需通报机构清单与各机构 UTC 回执截止时刻。
 * 时间均为 UTC。
 */
public record Notification(
        long id,
        long incidentId,
        long escalationId,
        Instant createdAt,
        Instant updatedAt) {
}
