package com.example.starter.coral;

import java.time.Instant;

/**
 * 移植记录查询中附带的封存摘要；未封存时sealed=false且数量为null。
 */
public record SealSummary(
        boolean sealed,
        Instant sealedAt,
        Integer aliveCount,
        Integer deadCount,
        Integer pendingCount) {

    public static SealSummary notSealed() {
        return new SealSummary(false, null, null, null, null);
    }
}