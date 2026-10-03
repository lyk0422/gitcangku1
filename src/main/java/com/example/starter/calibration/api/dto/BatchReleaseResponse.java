package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次放行响应。统计值在放行时封存进不可变快照；无纳入读数时 mean/min/max 为 null。
 *
 * @param batchKey      业务批次键
 * @param revision      放行的测量修订版本
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 * @param includedCount 纳入统计的读数条数
 * @param excludedCount 已排除的读数条数
 * @param meanValue     纳入读数均值（十进制字符串）；无纳入读数为 null
 * @param minValue      纳入读数最小值（十进制字符串）；无纳入读数为 null
 * @param maxValue      纳入读数最大值（十进制字符串）；无纳入读数为 null
 */
public record BatchReleaseResponse(
        String batchKey,
        int revision,
        String releasedBy,
        Instant releasedAt,
        int includedCount,
        int excludedCount,
        String meanValue,
        String minValue,
        String maxValue) {
}