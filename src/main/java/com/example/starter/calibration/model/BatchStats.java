package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次统计重算历史。每次重算追加不可变记录；includedCount 为 0 时均值/最值为 null。
 *
 * @param id            统计记录 ID（自增）
 * @param batchId       批次 ID
 * @param revision      重算时的批次测量修订版本
 * @param reason        重算原因：CREATE 批次创建 / SUBMIT 读数提交 / EXCLUDE 读数排除
 * @param readingCount  读数总数（含已排除）
 * @param includedCount 计入统计的读数条数
 * @param excludedCount 已排除的读数条数
 * @param meanValue     计入读数均值（HALF_UP 12 位）；无计入读数时为 null
 * @param minValue      计入读数最小值；无计入读数时为 null
 * @param maxValue      计入读数最大值；无计入读数时为 null
 * @param createdAt     重算时间（UTC）
 */
public record BatchStats(
        long id,
        long batchId,
        int revision,
        String reason,
        int readingCount,
        int includedCount,
        int excludedCount,
        BigDecimal meanValue,
        BigDecimal minValue,
        BigDecimal maxValue,
        Instant createdAt) {
}
