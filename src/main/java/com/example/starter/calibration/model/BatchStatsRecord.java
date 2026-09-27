package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次统计重算记录（不可变）。读数提交与读数排除时各写一条，封存当时统计结果；
 * 无计入读数时 mean/min/max 为 null。
 *
 * @param id            统计记录 ID（自增）
 * @param batchId       所属批次 ID
 * @param batchRevision 统计对应的批次修订版本号
 * @param triggerType   触发类型：READING_SUBMIT 读数提交 / READING_EXCLUDED 读数排除
 * @param includedCount 计入统计的读数条数
 * @param excludedCount 已排除的读数条数
 * @param meanValue     计入读数计算值的均值（HALF_UP 12 位）；无计入读数时为 null
 * @param minValue      计入读数计算值的最小值；无计入读数时为 null
 * @param maxValue      计入读数计算值的最大值；无计入读数时为 null
 * @param createdAt     统计时间（UTC）
 */
public record BatchStatsRecord(
        long id,
        long batchId,
        int batchRevision,
        String triggerType,
        int includedCount,
        int excludedCount,
        BigDecimal meanValue,
        BigDecimal minValue,
        BigDecimal maxValue,
        Instant createdAt) {
}
