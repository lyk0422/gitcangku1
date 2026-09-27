package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant；
 * segregationCategory 为批次声明的隔离类别，null 表示尚未声明。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        String segregationCategory,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt
) {
}
