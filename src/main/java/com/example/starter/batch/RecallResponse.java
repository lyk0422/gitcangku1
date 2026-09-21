package com.example.starter.batch;

import java.time.Instant;

/**
 * 召回响应。
 *
 * @param reason      召回原因
 * @param actorId     召回操作人
 * @param recalledAt  召回时间（UTC）
 * @param batchStatus 召回后批次状态（RECALLED）
 */
public record RecallResponse(
        String reason,
        String actorId,
        Instant recalledAt,
        BatchStatus batchStatus) {
}
