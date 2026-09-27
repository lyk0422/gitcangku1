package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次读数响应。deviation 为实际偏差 |读数-目标值|；outlier 为 true 时 outlierId 给出离群记录 ID。
 *
 * @param id               读数 ID
 * @param readingKey       业务读数键
 * @param batchKey         所属批次键
 * @param batchRevision    提交时的批次测量修订版本
 * @param value            读数值（十进制字符串）
 * @param targetValue      批次目标值（十进制字符串）
 * @param allowedDeviation 批次允许偏差，含端点（十进制字符串）
 * @param deviation        实际偏差 |读数-目标值|（十进制字符串）
 * @param outlier          是否离群（实际偏差 &gt; 允许偏差）
 * @param outlierId        离群记录 ID；非离群为 null
 * @param excluded         是否已被 REJECT 裁决排除
 * @param submittedBy      读数提交人
 * @param createdAt        提交时间（UTC）
 */
public record ReadingResponse(
        long id,
        String readingKey,
        String batchKey,
        int batchRevision,
        String value,
        String targetValue,
        String allowedDeviation,
        String deviation,
        boolean outlier,
        Long outlierId,
        boolean excluded,
        String submittedBy,
        Instant createdAt) {
}
