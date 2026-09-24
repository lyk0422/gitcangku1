package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 批次有效期视图。expired 为当前服务端时钟下的到期判定结果；
 * remainingMinutes 为相对当前时刻的剩余分钟，到期后为 0（不返回负值）。
 */
public record ShelfLifeResponse(
        String batchKey,
        int shelfLifeMinutes,
        Instant producedAt,
        Instant baseExpiresAt,
        Instant expiresAt,
        Instant now,
        boolean expired,
        long remainingMinutes,
        int extensionCount,
        int extendedMinutes
) {
}
