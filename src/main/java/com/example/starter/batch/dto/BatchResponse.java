package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * generation 为返工代次：初始批次为 0，拆分子批继承父批代次，返工批次为原批次代次加一。
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
