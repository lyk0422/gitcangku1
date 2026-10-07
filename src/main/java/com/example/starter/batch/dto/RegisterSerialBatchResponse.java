package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 序号根批登记响应。intervals 为规范化（排序、相邻合并）后的根批序号域；
 * quantity 等于区间总长度。producedAt/createdAt 为 UTC instant。
 */
public record RegisterSerialBatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        int quantity,
        List<IntervalView> intervals,
        List<String> requiredTests,
        Instant createdAt
) {
}
