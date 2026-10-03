package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量批次响应。统计字段为最近一次重算结果；无读数时均值/最值为 null。
 *
 * @param id              批次 ID
 * @param batchKey        业务批次键
 * @param instrumentId    仪器 ID
 * @param targetValue     目标值（十进制字符串）
 * @param tolerance       允许偏差（十进制字符串，要求值）
 * @param revision        当前测量修订版本
 * @param status          状态：OPEN / RELEASED
 * @param readingCount    参与统计的读数条数（未排除）
 * @param excludedCount   已排除读数条数
 * @param meanValue       未排除读数均值（十进制字符串）；无读数为 null
 * @param minValue        未排除读数最小值；无读数为 null
 * @param maxValue        未排除读数最大值；无读数为 null
 * @param pendingOutliers 待裁决离群记录数（实际值，放行要求为 0）
 * @param releasedBy      最近一次放行人；未放行或已重新打开为 null
 * @param releasedAt      最近一次放行时间（UTC）；未放行或已重新打开为 null
 * @param createdAt       创建时间（UTC）
 */
public record BatchResponse(
        long id,
        String batchKey,
        String instrumentId,
        String targetValue,
        String tolerance,
        int revision,
        String status,
        int readingCount,
        int excludedCount,
        String meanValue,
        String minValue,
        String maxValue,
        int pendingOutliers,
        String releasedBy,
        Instant releasedAt,
        Instant createdAt) {
}