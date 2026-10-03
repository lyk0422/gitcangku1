package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离群记录。读数超出允许偏差时自动生成，封存目标值、允许偏差与读数，后续修订不改写。
 *
 * @param id               离群记录 ID（自增）
 * @param batchId          所属批次 ID
 * @param readingId        触发离群的读数 ID
 * @param batchRevision    检测时的测量修订版本
 * @param targetValue      封存的目标值
 * @param allowedDeviation 封存的允许偏差
 * @param readingValue     封存的读数值
 * @param actualDeviation  实际偏差 |读数-目标值|
 * @param excess           超出量 = 实际偏差 - 允许偏差
 * @param status           状态：PENDING 待裁决 / ACCEPTED 保留 / REJECTED 排除
 * @param decidedAt        裁决时间（UTC），未裁决为 null
 * @param createdAt        检测时间（UTC）
 */
public record OutlierRecord(
        long id,
        long batchId,
        long readingId,
        int batchRevision,
        BigDecimal targetValue,
        BigDecimal allowedDeviation,
        BigDecimal readingValue,
        BigDecimal actualDeviation,
        BigDecimal excess,
        OutlierStatus status,
        Instant decidedAt,
        Instant createdAt) {
}