package com.example.starter.incident;

import java.time.Instant;

/**
 * 目标事件的接收代理人登记，对应 incident_receiving_delegates 表。
 * 除当前指挥人外，登记的代理人也具该事件的互助资源接收权限；
 * (incident_id, delegate) 唯一。时间均为 UTC。
 */
public record IncidentDelegate(
        long id,
        long incidentId,
        String delegate,
        String registeredBy,
        Instant createdAt) {
}
