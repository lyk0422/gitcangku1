package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源授权租约实体，对应 incident_task_leases 表。
 * 资源分配给任务时必须携带 UTC 到期时刻与授权版本；有效期按左闭右开
 * [assignedAt, expiresAt) 解释，当前时刻 >= expiresAt 即到期。
 * releasedBy/releasedAt 仅 RELEASED 有值。时间均为 UTC。
 */
public record ResourceLease(
        long id,
        long taskId,
        long incidentId,
        String resourceKey,
        int authVersion,
        String assignedBy,
        Instant assignedAt,
        Instant expiresAt,
        LeaseStatus status,
        String releasedBy,
        Instant releasedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 按给定时刻判断 ACTIVE 租约是否已到期（左闭右开：now >= expiresAt 即到期）。
     */
    public boolean expiredAt(Instant now) {
        return status == LeaseStatus.ACTIVE && !now.isBefore(expiresAt);
    }
}
