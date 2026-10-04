package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 清洗记录响应。status 为 PENDING/CONFIRMED；residualAmount 保留提交时的未舍入原值；
 * confirmedBy/confirmedAt 在未确认时为 null。
 */
public record CleaningResponse(
        String cleaningId,
        String lineId,
        String fromBatchKey,
        String toBatchKey,
        String residualAmount,
        String status,
        String submittedBy,
        Instant submittedAt,
        String confirmedBy,
        Instant confirmedAt,
        int version
) {
}