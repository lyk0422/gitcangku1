package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次放行快照（不可变）。放行时封存修订版本与统计结果；
 * 后续测量修订不得改写该快照，新增离群事实只能进入后续修订版本。
 *
 * @param id            放行快照 ID（自增）
 * @param batchId       所属批次 ID
 * @param batchRevision 放行的批次修订版本号
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 * @param includedCount 放行时计入统计的读数条数
 * @param excludedCount 放行时已排除的读数条数
 * @param meanValue     放行时计入读数计算值的均值（HALF_UP 12 位）
 * @param minValue      放行时计入读数计算值的最小值
 * @param maxValue      放行时计入读数计算值的最大值
 */
public record BatchRelease(
        long id,
        long batchId,
        int batchRevision,
        String releasedBy,
        Instant releasedAt,
        int includedCount,
        int excludedCount,
        BigDecimal meanValue,
        BigDecimal minValue,
        BigDecimal maxValue) {
}
