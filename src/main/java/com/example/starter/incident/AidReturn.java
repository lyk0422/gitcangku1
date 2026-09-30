package com.example.starter.incident;

import java.time.Instant;

/**
 * 互助租约分次归还流水实体，对应 resource_aid_returns 表。
 * quantity 为本次实际归还数量（件，正数）；returnedAt 为归还 UTC 时刻。
 */
public record AidReturn(
        long id,
        long leaseId,
        int quantity,
        String returnedBy,
        Instant returnedAt,
        Instant createdAt) {
}
