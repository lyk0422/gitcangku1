package com.example.starter.certificate.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应：parentCount 为实际父批数量（要求 2～5）；createdAt 为 UTC instant。
 */
public record MergeBatchesResponse(
        String childBatchKey,
        String childBatchNo,
        String productCode,
        BatchStatus status,
        int parentCount,
        List<String> parentKeys,
        List<String> requiredTests,
        Instant createdAt
) {
}
