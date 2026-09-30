package com.example.starter.incident;

import java.time.Instant;

/**
 * 跨事件互助资源租约实体，对应 incident_resource_leases 表（目标事件侧）。
 * requestId 全局唯一，同键同参重放首次双侧账务结果，同键改数量或目标事件返回 409；
 * quantity 为征调总量（正整数，件），returnedQty 为分次归还累计量，
 * 恒满足 0 ≤ returnedQty ≤ quantity；到期时刻 expiresAt 仅为元数据，
 * 到期不产生自动归还；closedAt 仅在最后一次归还恰好结清时（CLOSED）有值。
 * sourceIncidentId/targetIncidentId 落库后永不改变，历史租约保留首次征调两端事件。
 */
public record ResourceLease(
        long id,
        String requestId,
        long sourceIncidentId,
        long targetIncidentId,
        String resourceCode,
        long quantity,
        long returnedQty,
        LeaseStatus status,
        Instant expiresAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt) {

    /** 待归还数量（件）：征调总量减已归还累计量。 */
    public long outstandingQty() {
        return quantity - returnedQty;
    }
}
