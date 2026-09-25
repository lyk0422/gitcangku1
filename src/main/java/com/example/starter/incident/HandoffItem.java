package com.example.starter.incident;

import java.time.Instant;

/**
 * 互助交接资源项实体，对应 resource_handoff_items 表。
 * (handoffId, resourceKey) 唯一；settledAt 为该资源归还来源的结算 UTC 时间，未结算为空。
 */
public record HandoffItem(
        long id,
        long handoffId,
        String resourceKey,
        Instant settledAt) {
}
