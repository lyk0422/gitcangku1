package com.example.starter.model;

import java.time.Instant;

/**
 * 起飞拒绝历史，只增不改，后续作业不得覆盖。
 *
 * @param id 主键
 * @param flightId 航班 ID
 * @param requestId 触发拒绝的请求号
 * @param reason 拒绝原因
 * @param attemptedAt 尝试确认时刻（UTC）
 */
public record TakeoffRejection(
        Long id,
        long flightId,
        String requestId,
        String reason,
        Instant attemptedAt) {
}