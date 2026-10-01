package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次放行快照（表 batch_release）。每次放行生成一条，封存放行时的修订版本与统计；
 * 后续测量修订不得改写旧批次放行快照。
 *
 * @param id            放行快照 ID（自增）
 * @param batchId       所属批次 ID
 * @param revision      放行时测量修订版本
 * @param includedCount 放行时未排除读数数量
 * @param excludedCount 放行时已排除读数数量
 * @param meanValue     放行时未排除读数计算值均值（12 位小数）；无未排除读数时为 null
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 */
public record BatchRelease(
        long id,
        long batchId,
        int revision,
        int includedCount,
        int excludedCount,
        BigDecimal meanValue,
        String releasedBy,
        Instant releasedAt) {
}