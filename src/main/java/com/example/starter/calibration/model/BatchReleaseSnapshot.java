package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次放行快照（不可变）。放行时封存统计与操作人；后续测量修订不得改写旧快照。
 *
 * @param id            快照 ID（自增）
 * @param batchId       所属批次 ID
 * @param batchRevision 放行的测量修订版本
 * @param includedCount 放行时纳入统计的读数条数
 * @param excludedCount 放行时已排除的读数条数
 * @param meanValue     放行时纳入读数均值，无纳入读数为 null
 * @param minValue      放行时纳入读数最小值，无纳入读数为 null
 * @param maxValue      放行时纳入读数最大值，无纳入读数为 null
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 */
public record BatchReleaseSnapshot(
        long id,
        long batchId,
        int batchRevision,
        int includedCount,
        int excludedCount,
        BigDecimal meanValue,
        BigDecimal minValue,
        BigDecimal maxValue,
        String releasedBy,
        Instant releasedAt) {
}