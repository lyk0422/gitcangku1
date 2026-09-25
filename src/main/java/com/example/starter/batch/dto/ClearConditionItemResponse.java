package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 核销条件子项响应：携带核销后的批次状态与剩余未核销子项。
 * 全部子项核销完成时 batchStatus 为 RELEASED，pendingItems 为空。
 */
public record ClearConditionItemResponse(
        String batchKey,
        String conditionKey,
        String itemKey,
        String clearedBy,
        String clearedRole,
        BatchStatus batchStatus,
        List<String> pendingItems,
        Instant clearedAt
) {
}
