package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 包装计划响应：计划包装数量与连续标签号段（两端含）。
 */
public record PackagingPlanResponse(
        String batchKey,
        int plannedQuantity,
        long labelStart,
        long labelEnd,
        Instant createdAt
) {
}
