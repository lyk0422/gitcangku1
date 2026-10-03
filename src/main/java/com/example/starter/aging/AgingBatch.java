package com.example.starter.aging;

import java.time.Instant;

/**
 * 熟成批次。storedAt 为入库 UTC 时刻；status 取值 AGING（熟成中）/ RELEASED（已放行）。
 */
public record AgingBatch(
        long id,
        String batchCode,
        String requestId,
        Instant storedAt,
        int targetDays,
        String status,
        Instant createdAt) {

    public static final String STATUS_AGING = "AGING";
    public static final String STATUS_RELEASED = "RELEASED";
}