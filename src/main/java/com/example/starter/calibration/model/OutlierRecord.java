package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离群记录。检测时封存目标值、允许偏差、读数与实际偏差；状态随裁决流转。
 *
 * @param id            离群记录 ID（自增）
 * @param batchId       所属批次 ID
 * @param readingId     离群读数 ID
 * @param batchRevision 检测时批次修订版本快照
 * @param targetValue   检测时目标值封存
 * @param tolerance     检测时允许偏差封存（要求值）
 * @param readingValue  离群读数封存（实际值）
 * @param deviation     实际偏差 |读数-目标值|
 * @param status        状态：PENDING 待裁决 / ACCEPTED 已接受 / REJECTED 已拒绝
 * @param detectedAt    检测时间（UTC）
 * @param adjudicatedAt 裁决时间（UTC）；未裁决为 null
 */
public record OutlierRecord(
        long id,
        long batchId,
        long readingId,
        int batchRevision,
        BigDecimal targetValue,
        BigDecimal tolerance,
        BigDecimal readingValue,
        BigDecimal deviation,
        OutlierStatus status,
        Instant detectedAt,
        Instant adjudicatedAt) {
}