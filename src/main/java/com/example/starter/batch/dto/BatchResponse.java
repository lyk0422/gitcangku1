package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt/expiresAt 为 UTC instant；
 * expiresAt 为当前有效期截止（延期生效后顺延），expired 为查询时刻是否已到期的标识。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt,
        Instant expiresAt,
        boolean expired
) {
}
