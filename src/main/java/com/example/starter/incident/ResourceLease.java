package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源租约实体，对应 resource_leases 表。
 * 批量派工校验资源依赖后原子获取；同一资源同一时刻至多一条 ACTIVE 租约。
 * releasedAt 仅 RELEASED 有值。
 */
public record ResourceLease(
        long id,
        long incidentId,
        long taskId,
        String resourceKey,
        String status,
        Instant createdAt,
        Instant releasedAt) {
}
