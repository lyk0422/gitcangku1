package com.example.starter.incident;

import java.time.Instant;

/**
 * 演练批次清理历史实体，对应 drill_cleanups 表。
 * 仅记录成功清理；cleanupKey 为调用方幂等键，同键同参重放首次结果。
 */
public record CleanupRecord(
        long id,
        String cleanupKey,
        String batchKey,
        int deletedIncidents,
        String actor,
        Instant createdAt) {
}
