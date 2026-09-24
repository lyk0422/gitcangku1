package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * shelfLifeMinutes 为不可改写的保质分钟；baseExpiresAt 为初始有效期；
 * expiresAt 为当前有效期（含已生效延期）；expired 为当前服务端时钟下的到期标识。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt,
        int shelfLifeMinutes,
        Instant baseExpiresAt,
        Instant expiresAt,
        boolean expired
) {
}
