package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源租约实体，对应 resource_leases 表（按版本追加，历史版本不改写）。
 * leaseVersion 初始分配为 1，每次续期 +1；assignedBy 为原分配人（各版本一致），
 * authorizedBy 为本版本授权人，authorizerRole 仅续期版本有值（初始分配为 null）。
 * 授权区间为 [assignedAt, expiresAt) 左闭右开，expiresAt 必须严格晚于 assignedAt；
 * endedAt 仅 SUPERSEDED/RELEASED/EXPIRED 有值。时间均为 UTC。
 */
public record ResourceLease(
        long id,
        long resourceId,
        String resourceKey,
        long incidentId,
        long taskId,
        int leaseVersion,
        String authVersion,
        String assignedBy,
        String authorizedBy,
        String authorizerRole,
        Instant assignedAt,
        Instant expiresAt,
        LeaseStatus status,
        Instant endedAt,
        Instant createdAt) {

    /**
     * 判断在指定 UTC 时刻授权是否已到期（区间左闭右开：now ≥ expiresAt 即到期）。
     */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
