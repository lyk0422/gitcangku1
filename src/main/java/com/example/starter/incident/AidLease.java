package com.example.starter.incident;

import java.time.Instant;

/**
 * 跨事件互助资源征调租约实体，对应 resource_aid_leases 表。
 * 来源事件与目标事件在首次征调时固定，历史租约始终保留这两个事件；
 * quantity 为征调总量（件），returnedQuantity 为分次归还累计量，
 * 恒满足 0 &lt;= returnedQuantity &lt;= quantity；
 * exclusive 为 true 表示资源被本租约排他占用；
 * status 仅 ACTIVE→CLOSED，恰好还完时关闭并一次性释放来源额度；
 * expiresAt 为 UTC 到期时刻，到期不自动虚构归还。
 * closedAt 仅 CLOSED 有值，否则为空。
 */
public record AidLease(
        long id,
        String requestId,
        long sourceIncidentId,
        long targetIncidentId,
        String resourceCode,
        int quantity,
        int returnedQuantity,
        boolean exclusive,
        AidLeaseStatus status,
        Instant expiresAt,
        String createdBy,
        Instant createdAt,
        Instant closedAt) {

    /** 当前未归还数量（件）。 */
    public int outstandingQuantity() {
        return quantity - returnedQuantity;
    }
}
