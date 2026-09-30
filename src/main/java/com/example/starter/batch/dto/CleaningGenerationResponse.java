package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 清洁代次更新结果。updatedAt 为 UTC instant。
 */
public record CleaningGenerationResponse(
        String batchKey,
        Integer cleaningGeneration,
        Instant updatedAt
) {
}
