package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 隔离类别声明响应；declaredAt 为 UTC instant。
 */
public record SegregationCategoryResponse(
        String batchKey,
        String segregationCategory,
        Instant declaredAt
) {
}
