package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant；supplierId 为登记时的供应商标识。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        String supplierId,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt
) {
}
