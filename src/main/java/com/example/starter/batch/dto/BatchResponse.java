package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * generation 为返工重投代次：初始批次与拆分子批按各自链代次，返工重投生成的批次代次为原批次加一。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        int generation,
        List<String> requiredTests,
        Instant createdAt
) {
}
