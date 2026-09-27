package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量批次。配置目标值与允许偏差；读数超差自动生成离群记录并阻断放行。
 *
 * @param id               批次 ID（自增）
 * @param batchKey         业务批次键，全局唯一（幂等键）
 * @param instrumentId     仪器 ID
 * @param certificateId    绑定的校准证书 ID（证书版本）
 * @param targetValue      目标值
 * @param allowedDeviation 允许偏差（含端点），非负
 * @param revision         当前测量修订版本，从 1 开始；已放行批次新增读数时 +1
 * @param status           状态：OPEN 待放行 / RELEASED 已放行
 * @param createdAt        创建时间（UTC）
 */
public record MeasurementBatch(
        long id,
        String batchKey,
        String instrumentId,
        long certificateId,
        BigDecimal targetValue,
        BigDecimal allowedDeviation,
        int revision,
        BatchStatus status,
        Instant createdAt) {
}
