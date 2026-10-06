package com.example.starter.batch.dto;

import com.example.starter.batch.AssayRoundStatus;

import java.time.Instant;

/**
 * 平行测定轮次响应。meanMin/meanMax 为均值闭区间，maxRange 为最大极差；
 * version 为当前已写入读数条数（乐观锁版本）；sealedAt 未封结时为 null。
 */
public record AssayRoundResponse(
        long roundId,
        String batchKey,
        String testItem,
        int roundNo,
        String meanMin,
        String meanMax,
        String maxRange,
        int version,
        AssayRoundStatus status,
        String createdBy,
        Instant createdAt,
        Instant sealedAt
) {
}
