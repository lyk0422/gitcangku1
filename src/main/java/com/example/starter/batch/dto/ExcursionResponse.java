package com.example.starter.batch.dto;

import com.example.starter.batch.ExcursionStatus;
import com.example.starter.batch.ExcursionType;

import java.time.Instant;

/**
 * 偏差事件响应。intervalFrom/intervalTo 仅缺测偏差有值；resolvedAt 未解除时为 null。
 * limitVersion 为偏差生成时的限值版本，处置确认必须针对同一版本。
 */
public record ExcursionResponse(
        String excursionKey,
        String batchKey,
        String segmentKey,
        ExcursionType eventType,
        int limitVersion,
        long readingId,
        Instant intervalFrom,
        Instant intervalTo,
        String detail,
        ExcursionStatus status,
        Instant createdAt,
        Instant resolvedAt
) {
}
