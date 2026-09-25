package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.SegregationLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * componentVersion 为当前不可变成分版本号（恒 ≥1）；allergenCodes 为规范化代码列表；
 * segregationLevel 为当前隔离级别；stockQuantity 为当前库存余额。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt,
        int componentVersion,
        List<String> allergenCodes,
        SegregationLevel segregationLevel,
        BigDecimal stockQuantity
) {
}
