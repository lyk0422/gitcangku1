package com.example.starter.batch.dto;

import com.example.starter.batch.DispositionStatus;

import java.time.Instant;
import java.util.List;

/**
 * 召回处置单响应。batches 为提交时冻结的闭包版本/状态/路径与分类快照，确认后不可变。
 * 各时间戳为 ISO-8601 UTC instant；未发生的动作对应字段为 null。
 */
public record DispositionResponse(
        String dispositionKey,
        String ancestorKey,
        DispositionStatus status,
        String submittedBy,
        int dispositionVersion,
        String confirmedBy,
        String rejectedBy,
        String rejectReason,
        String holdReason,
        Instant submittedAt,
        Instant confirmedAt,
        Instant rejectedAt,
        Instant cancelledAt,
        List<DispositionBatchResponse> batches
) {
}
