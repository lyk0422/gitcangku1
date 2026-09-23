package com.example.starter.incident;

import java.time.Instant;

/**
 * 共享资源租约实体，对应 resource_leases 表。
 * 同任务同资源至多一条 ACTIVE 租约；version 为乐观版本（授予时为 1，撤销时递增），
 * 抢占计划必须提交受害租约当前版本。grantedAt 仅 ACTIVE 起有值，
 * releasedAt 仅 RELEASED 有值，revokedAt/revokeReason 仅 REVOKED 有值。
 * requestId 为最近导致该租约生效/撤销的抢占请求标识，仅用于审计。
 * 时间均为 UTC。
 */
public record ResourceLease(
        long id,
        String leaseKey,
        long resourceId,
        long taskId,
        long incidentId,
        int quantity,
        LeaseStatus status,
        long version,
        Instant grantedAt,
        Instant releasedAt,
        Instant revokedAt,
        String revokeReason,
        String requestId,
        Instant createdAt,
        Instant updatedAt) {
}
