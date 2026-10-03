package com.example.starter.model;

import java.time.Instant;

/**
 * 起飞放行记录，固化所用完成记录快照；同一完成记录只能放行一次。
 *
 * @param id 主键
 * @param requestId 幂等请求号，唯一
 * @param flightId 航班 ID
 * @param completionId 所用完成记录 ID，唯一
 * @param confirmedAt 起飞确认时刻（UTC），严格早于截止时刻
 * @param fluidType 固化液型快照
 * @param completedAt 固化完成时刻快照（UTC）
 * @param deadlineAt 固化截止时刻快照（UTC，不含）
 */
public record TakeoffClearance(
        Long id,
        String requestId,
        long flightId,
        long completionId,
        Instant confirmedAt,
        FluidType fluidType,
        Instant completedAt,
        Instant deadlineAt) {
}