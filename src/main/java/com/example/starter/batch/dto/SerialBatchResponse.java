package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 新类型根批登记响应。serialIntervals 为规范化后的闭区间集合；
 * quantity 等于区间总长度；version 为乐观版本号，登记时为 1。
 */
public record SerialBatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        long quantity,
        List<SerialIntervalView> serialIntervals,
        List<String> requiredTests,
        int version,
        Instant createdAt
) {
}
