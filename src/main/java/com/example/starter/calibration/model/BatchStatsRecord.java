package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次统计重算记录。不可变；提交读数、REJECT 裁决、放行时各写一条。
 *
 * @param id            统计记录 ID（自增）
 * @param batchId       批次 ID
 * @param revision      统计时的批次修订版本
 * @param triggerAction 触发动作：SUBMIT / ADJUDICATION / RELEASE
 * @param readingCount  参与统计的读数条数（未排除）
 * @param excludedCount 已排除读数条数
 * @param meanValue     未排除读数均值，HALF_UP 12 位小数；无读数为 null
 * @param minValue      未排除读数最小值；无读数为 null
 * @param maxValue      未排除读数最大值；无读数为 null
 * @param createdAt     统计时间（UTC）
 */
public record BatchStatsRecord(
        long id,
        long batchId,
        int revision,
        String triggerAction,
        int readingCount,
        int excludedCount,
        BigDecimal meanValue,
        BigDecimal minValue,
        BigDecimal maxValue,
        Instant createdAt) {
}