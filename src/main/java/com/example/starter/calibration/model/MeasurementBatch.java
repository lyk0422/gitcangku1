package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量批次。可配置目标值与允许偏差；合格区间按左闭右开解释：
 * [targetValue - allowedDeviation, targetValue + allowedDeviation)。
 *
 * @param id               批次 ID（自增）
 * @param batchKey         业务批次键，全局唯一（幂等键）
 * @param instrumentId     仪器 ID
 * @param targetValue      目标值，最多 6 位小数
 * @param allowedDeviation 允许偏差（>=0），最多 6 位小数
 * @param revision         测量修订版本号，从 1 开始，每次修订 +1
 * @param status           状态：OPEN 开放 / RELEASED 当前修订已放行
 * @param createdAt        创建时间（UTC）
 */
public record MeasurementBatch(
        long id,
        String batchKey,
        String instrumentId,
        BigDecimal targetValue,
        BigDecimal allowedDeviation,
        int revision,
        BatchStatus status,
        Instant createdAt) {
}
