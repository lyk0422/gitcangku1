package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 召回响应，携带召回后的批次状态（恒为 RECALLED）。
 */
public record RecallResponse(
        String batchKey,
        String actorId,
        String reason,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
