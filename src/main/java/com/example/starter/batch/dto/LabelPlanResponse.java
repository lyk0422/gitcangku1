package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 标签计划响应。号段端点均含；createdAt 为 UTC instant。
 */
public record LabelPlanResponse(
        String batchKey,
        int plannedQuantity,
        long labelRangeStart,
        long labelRangeEnd,
        Instant createdAt
) {
}
