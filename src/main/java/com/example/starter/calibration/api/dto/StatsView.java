package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次统计记录视图。无纳入读数时 mean/min/max 为 null。
 *
 * @param id            统计记录 ID
 * @param batchRevision 统计对应的测量修订版本
 * @param includedCount 纳入统计的读数条数
 * @param excludedCount 已排除的读数条数
 * @param meanValue     纳入读数均值（十进制字符串）；无纳入读数为 null
 * @param minValue      纳入读数最小值（十进制字符串）；无纳入读数为 null
 * @param maxValue      纳入读数最大值（十进制字符串）；无纳入读数为 null
 * @param trigger       触发源：READING_SUBMITTED / VERDICT_REJECT / RELEASE
 * @param createdAt     重算时间（UTC）
 */
public record StatsView(
        long id,
        int batchRevision,
        int includedCount,
        int excludedCount,
        String meanValue,
        String minValue,
        String maxValue,
        String trigger,
        Instant createdAt) {
}