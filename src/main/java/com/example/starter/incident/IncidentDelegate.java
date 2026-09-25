package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件代理人实体，对应 incident_delegates 表。
 * (incidentId, delegate) 唯一；代理人具互助交接接收权限。
 * 时间均为 UTC。
 */
public record IncidentDelegate(
        long id,
        long incidentId,
        String delegate,
        String registeredBy,
        Instant createdAt) {
}
