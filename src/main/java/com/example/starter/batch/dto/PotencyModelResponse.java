package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 效价模型版本响应。createdAt 为 UTC instant。
 */
public record PotencyModelResponse(
        int version,
        int scale,
        String roundingMode,
        Instant createdAt
) {
}
