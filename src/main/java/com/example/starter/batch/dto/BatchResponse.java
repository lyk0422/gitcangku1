package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * quantity/maxSampleQuantity/consumedQuantity/remainingQuantity 仅新类型数量批次有值，
 * 普通批次均为 null；remainingQuantity = quantity - consumedQuantity。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt,
        Integer quantity,
        Integer maxSampleQuantity,
        Integer consumedQuantity,
        Integer remainingQuantity
) {
}
