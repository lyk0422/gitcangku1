package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次放行快照响应（不可变）。
 *
 * @param batchKey      批次键
 * @param batchRevision 放行的批次修订版本号
 * @param releasedBy    放行人
 * @param releasedAt    放行时间（UTC）
 * @param includedCount 放行时计入统计的读数条数
 * @param excludedCount 放行时已排除的读数条数
 * @param meanValue     放行时计入读数计算值的均值（HALF_UP 12 位，十进制字符串）
 * @param minValue      放行时计入读数计算值的最小值（十进制字符串）
 * @param maxValue      放行时计入读数计算值的最大值（十进制字符串）
 */
public record BatchReleaseResponse(
        String batchKey,
        int batchRevision,
        String releasedBy,
        Instant releasedAt,
        int includedCount,
        int excludedCount,
        String meanValue,
        String minValue,
        String maxValue) {
}
