package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 包装计划登记响应。
 *
 * @param batchKey          批次业务键
 * @param plannedQuantity   计划包装数量（正整数）
 * @param labelRangeStart   连续标签号段起点（含）
 * @param labelRangeEnd     连续标签号段终点（含）
 * @param sealedQuantity    当前活跃封箱数量之和；刚登记时为 0
 * @param usedLabelCount    当前已用（活跃占用）标签数；刚登记时为 0
 * @param remainingQuantity 距计划数量的差额 = plannedQuantity - sealedQuantity（非负）
 * @param createdAt         登记时间，ISO-8601 UTC instant
 */
public record PackingPlanResponse(
        String batchKey,
        long plannedQuantity,
        long labelRangeStart,
        long labelRangeEnd,
        long sealedQuantity,
        long usedLabelCount,
        long remainingQuantity,
        Instant createdAt
) {
}
