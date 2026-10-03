package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次放行快照。不可变；后续测量修订不改写旧批次放行快照。
 *
 * @param id            快照 ID（自增）
 * @param batchId       批次 ID
 * @param revision      放行时的批次修订版本
 * @param readingCount  放行时参与统计的读数条数（未排除）
 * @param excludedCount 放行时已排除读数条数
 * @param meanValue     放行时未排除读数均值；无读数为 null
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 */
public record BatchReleaseSnapshot(
        long id,
        long batchId,
        int revision,
        int readingCount,
        int excludedCount,
        BigDecimal meanValue,
        String releasedBy,
        Instant releasedAt) {
}