package com.example.starter.incident;

import java.time.Instant;

/**
 * 演练批次登记/清理墓碑实体，对应 drill_batches 表。
 * 每个演练批次（drillBatchKey）首次使用时登记；成功清理后写入 cleanedAt/cleanupKey
 * 与 deletedIncidentCount，记录保留作为墓碑，使同批次标识不可再用于新演练。
 * cleanedAt 为空表示尚未清理。
 */
public record DrillBatch(
        long id,
        String batchKey,
        Instant cleanedAt,
        String cleanupKey,
        int deletedIncidentCount,
        Instant createdAt) {

    /**
     * 是否已完成清理（墓碑）。
     */
    public boolean isCleaned() {
        return cleanedAt != null;
    }
}
