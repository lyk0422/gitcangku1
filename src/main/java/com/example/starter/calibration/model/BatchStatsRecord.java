package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次统计重算记录（不可变）。每次重算追加一条；统计范围为该修订版本内未排除的读数。
 * 无纳入读数时 mean/min/max 为 null。
 *
 * @param id            统计记录 ID（自增）
 * @param batchId       所属批次 ID
 * @param batchRevision 统计对应的测量修订版本
 * @param includedCount 纳入统计的读数条数（未排除）
 * @param excludedCount 已排除的读数条数
 * @param meanValue     纳入读数均值（HALF_UP 12 位），无纳入读数为 null
 * @param minValue      纳入读数最小值，无纳入读数为 null
 * @param maxValue      纳入读数最大值，无纳入读数为 null
 * @param trigger       触发源
 * @param createdAt     重算时间（UTC）
 */
public record BatchStatsRecord(
        long id,
        long batchId,
        int batchRevision,
        int includedCount,
        int excludedCount,
        BigDecimal meanValue,
        BigDecimal minValue,
        BigDecimal maxValue,
        StatsTrigger trigger,
        Instant createdAt) {
}