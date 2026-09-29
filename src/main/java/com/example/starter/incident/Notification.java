package com.example.starter.incident;

import java.time.Instant;

/**
 * 外部通报单实体，对应 incident_notifications 表，每次遏制逾期升级创建一份，
 * 每事件至多一份（uk_notification_incident）。
 * createdAt 为通报创建（升级触发当时）的 UTC 时刻。
 */
public record Notification(
        long id,
        long incidentId,
        long escalationId,
        Instant createdAt) {
}
