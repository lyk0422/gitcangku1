package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量批次响应。meanValue 为 null 表示当前无未排除读数。
 *
 * @param id            批次 ID
 * @param batchKey      业务批次键
 * @param instrumentId  仪器 ID
 * @param targetValue   目标值（十进制字符串）
 * @param tolerance     允许偏差（十进制字符串）
 * @param revision      当前测量修订版本
 * @param status        状态：OPEN / RELEASED
 * @param readingCount  读数总数（含已排除）
 * @param includedCount 未排除读数数量
 * @param excludedCount 已排除读数数量
 * @param meanValue     未排除读数计算值均值（十进制字符串）；无未排除读数时为 null
 * @param createdAt     创建时间（UTC）
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
        int includedCount,
        int excludedCount,
        String meanValue,
        Instant createdAt) {
}