package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次放行成功响应。
 *
 * @param batchKey      批次键
 * @param revision      放行时的测量修订版本
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 * @param readingCount  放行时参与统计的读数条数（未排除）
 * @param excludedCount 放行时已排除读数条数
 * @param meanValue     放行时未排除读数均值（十进制字符串）；无读数为 null
 */
public record BatchReleaseResponse(
        String batchKey,
        int revision,
        String releasedBy,
        Instant releasedAt,
        int readingCount,
        int excludedCount,
        String meanValue) {
}