package com.example.starter.aging.domain;

import java.time.Instant;

/**
 * 放行记录。每批次最多一张；requestId 为全局唯一幂等键；releasedAt 为放行 UTC 时刻。
 */
public record ReleaseRecord(long id, long batchId, String requestId, Instant releasedAt) {
}