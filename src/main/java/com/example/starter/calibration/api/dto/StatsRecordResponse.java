package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次统计重算记录响应（诊断用）。
 *
 * @param revision      统计时的批次修订版本
 * @param triggerAction 触发动作：SUBMIT / ADJUDICATION / RELEASE
 * @param readingCount  参与统计的读数条数（未排除）
 * @param excludedCount 已排除读数条数
 * @param meanValue     未排除读数均值（十进制字符串）；无读数为 null
 * @param minValue      未排除读数最小值；无读数为 null
 * @param maxValue      未排除读数最大值；无读数为 null
 * @param createdAt     统计时间（UTC）
 */
public record StatsRecordResponse(
        int revision,
        String triggerAction,
        int readingCount,
        int excludedCount,
        String meanValue,
        String minValue,
        String maxValue,
        Instant createdAt) {
}