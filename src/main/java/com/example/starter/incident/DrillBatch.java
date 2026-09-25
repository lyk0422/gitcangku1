package com.example.starter.incident;

import java.time.Instant;

/**
 * 演练批次实体，对应 drill_batches 表。
 * ACTIVE 可继续向该批次写入演练事件；CLEANED 为清理后保留的墓碑，
 * 同 batchKey 不可再用于新演练事件。
 */
public record DrillBatch(
        String batchKey,
        String drillKey,
        DrillBatchStatus status,
        Instant createdAt,
        Instant cleanedAt) {
}
