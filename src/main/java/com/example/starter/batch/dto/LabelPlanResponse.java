package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 标签计划响应。号段按左闭右开 [labelStart, labelEnd) 解释。
 */
public record LabelPlanResponse(
        String batchKey,
        int plannedQuantity,
        long labelStart,
        long labelEnd,
        Instant createdAt
) {
}
