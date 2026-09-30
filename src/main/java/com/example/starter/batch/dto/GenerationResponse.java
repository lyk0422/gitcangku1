package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 清洁代次推进结果。清洁代次为正整数，只允许单调向前。
 */
public record GenerationResponse(
        String batchKey,
        int cleaningGeneration,
        Instant updatedAt
) {
}
