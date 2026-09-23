package com.example.starter.incident;

import java.time.Instant;

/**
 * 共享资源租约实体，对应 resource_leases 表。
 * leaseKey 全局唯一，同键同内容幂等、同键不同内容冲突；
 * 同一任务对同一资源至多一条 ACTIVE 租约。
 * version 为乐观版本：创建为 1，释放/撤销时加 1，抢占计划按 leaseKey+version 指定受害租约。
 * releasedAt 仅 RELEASED 有值，revokedAt 仅 REVOKED 有值。时间均为 UTC。
 */
public record ResourceLease(
        long id,
        String leaseKey,
        long resourceId,
        long incidentId,
        long taskId,
        int units,
        LeaseStatus status,
        long version,
        String requestId,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant releasedAt,
        Instant revokedAt) {

    /**
     * 判断两条租约申请的业务内容是否一致（用于 leaseKey 幂等比对）。
     */
    public boolean sameContent(long resourceId, long taskId, int units) {
        return this.resourceId == resourceId && this.taskId == taskId && this.units == units;
    }
}
