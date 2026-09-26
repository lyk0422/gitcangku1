package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 包装计划响应。标签号段左闭右开 [labelStart, labelEnd)。
 */
public record PackPlanResponse(
        String batchKey,
        int plannedQuantity,
        long labelStart,
        long labelEnd,
        Instant createdAt
) {
}
