package com.example.starter.incident;

import java.time.Instant;

/**
 * 可跨事件互助的空闲资源实体，对应 incident_resources 表。
 * 资源由其来源事件（ownerIncidentId）登记，登记时该事件须未关闭；
 * status=AVAILABLE 时可借出，存在 ACTIVE 交接时为 LEASED_OUT。
 * 时间均为 UTC。
 */
public record Resource(
        long id,
        String resourceKey,
        long ownerIncidentId,
        String label,
        String registeredBy,
        ResourceStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
