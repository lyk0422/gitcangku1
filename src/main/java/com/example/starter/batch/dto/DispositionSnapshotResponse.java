package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.DispositionCategory;

import java.time.Instant;
import java.util.List;

/**
 * 处置确认落账后不可变的路径与分类快照（仅 CONFIRMED 处置单存在）。
 */
public record DispositionSnapshotResponse(
        String dispositionKey,
        String batchKey,
        DispositionCategory category,
        BatchStatus previousStatus,
        BatchStatus finalStatus,
        long batchVersion,
        List<String> path,
        String reason,
        Instant createdAt
) {
}
