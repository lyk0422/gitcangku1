package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量批次（表 measurement_batch）。一批读数共享目标值与允许偏差配置；
 * 读数计算值超出 [目标值-允许偏差, 目标值+允许偏差]（含端点）时生成离群记录并阻断该批次放行。
 * 一行记录对应一个业务批次的当前状态；统计字段随读数提交与 REJECT 裁决在同一事务内重算。
 *
 * @param id            批次 ID（自增）
 * @param batchKey      业务批次键，全局唯一（幂等键）
 * @param instrumentId  仪器 ID
 * @param targetValue   目标值，最多 6 位小数
 * @param tolerance     允许偏差（±，含端点），非负，最多 6 位小数
 * @param revision      当前测量修订版本，从 1 开始；已放行批次创建后续修订时递增
 * @param status        状态：OPEN 开放 / RELEASED 已放行
 * @param readingCount  读数总数（含已排除）
 * @param includedCount 未排除读数数量
 * @param excludedCount 已排除读数数量（REJECT 裁决排除）
 * @param meanValue     未排除读数计算值均值（12 位小数）；无未排除读数时为 null
 * @param createdAt     创建时间（UTC）
 */
public record MeasurementBatch(
        long id,
        String batchKey,
        String instrumentId,
        BigDecimal targetValue,
        BigDecimal tolerance,
        int revision,
        BatchStatus status,
        int readingCount,
        int includedCount,
        int excludedCount,
        BigDecimal meanValue,
        Instant createdAt) {
}