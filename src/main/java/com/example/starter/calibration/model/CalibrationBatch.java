package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准测量批次。可配置目标值与允许偏差；读数与目标值之差的绝对值超过允许偏差即判离群。
 * 已放行批次发现新增离群事实时只能创建后续修订版本（revision 递增），旧放行快照不改写。
 *
 * @param id               批次 ID（自增）
 * @param batchKey         业务批次键，全局唯一
 * @param instrumentId     仪器 ID
 * @param targetValue      目标值，最多 6 位小数
 * @param allowedDeviation 允许偏差（非负），最多 6 位小数
 * @param revision         当前测量修订版本，从 1 开始
 * @param status           状态：OPEN 开放 / RELEASED 已放行
 * @param createdAt        创建时间（UTC）
 */
public record CalibrationBatch(
        long id,
        String batchKey,
        String instrumentId,
        BigDecimal targetValue,
        BigDecimal allowedDeviation,
        int revision,
        BatchStatus status,
        Instant createdAt) {
}