package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次统计重算记录响应（不可变历史）。
 *
 * @param batchRevision 统计对应的批次修订版本号
 * @param triggerType   触发类型：READING_SUBMIT / READING_EXCLUDED
 * @param includedCount 计入统计的读数条数
 * @param excludedCount 已排除的读数条数
 * @param meanValue     均值（HALF_UP 12 位，十进制字符串）；无计入读数时为 null
 * @param minValue      最小值（十进制字符串）；无计入读数时为 null
 * @param maxValue      最大值（十进制字符串）；无计入读数时为 null
 * @param createdAt     统计时间（UTC）
 */
public record StatsRecordResponse(
        int batchRevision,
        String triggerType,
        int includedCount,
        int excludedCount,
        String meanValue,
        String minValue,
        String maxValue,
        Instant createdAt) {
}
