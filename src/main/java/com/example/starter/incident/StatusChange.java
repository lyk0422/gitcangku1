package com.example.starter.incident;

import java.time.Instant;

/**
 * 状态流转历史实体，对应 incident_status_history 表。
 * fromStatus 在事件创建（首次落库）时为 null；occurredAt 为 UTC 时间。
 */
public record StatusChange(
        long id,
        long incidentId,
        IncidentStatus fromStatus,
        IncidentStatus toStatus,
        String actor,
        Instant occurredAt) {
}
