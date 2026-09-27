package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 包装计划响应。labelStart/labelEnd 构成闭区间连续标签号段；createdAt 为 UTC 登记时间。
 */
public record PackingPlanResponse(
        String batchKey,
        int plannedQuantity,
        long labelStart,
        long labelEnd,
        Instant createdAt
) {
}
