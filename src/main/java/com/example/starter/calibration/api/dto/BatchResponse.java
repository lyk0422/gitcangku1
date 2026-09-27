package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量批次响应。stats 为当前修订版本的最新统计（无统计记录时为 null）。
 *
 * @param id               批次 ID
 * @param batchKey         业务批次键
 * @param instrumentId     仪器 ID
 * @param targetValue      目标值（十进制字符串）
 * @param allowedDeviation 允许偏差（十进制字符串）
 * @param revision         当前测量修订版本号
 * @param status           状态：OPEN / RELEASED
 * @param createdAt        创建时间（UTC）
 * @param stats            当前修订版本最新统计；无统计记录时为 null
 * @param pendingOutliers  当前修订版本待裁决离群记录数
 */
public record BatchResponse(
        long id,
        String batchKey,
        String instrumentId,
        String targetValue,
        String allowedDeviation,
        int revision,
        String status,
        Instant createdAt,
        StatsResponse stats,
        int pendingOutliers) {
}
