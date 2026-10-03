package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 离群记录响应（诊断用）。封存检测时的目标值、允许偏差、读数与实际偏差。
 *
 * @param id            离群记录 ID
 * @param batchKey      所属批次键
 * @param readingId     离群读数 ID
 * @param readingKey    离群读数键
 * @param batchRevision 检测时批次修订版本快照
 * @param targetValue   检测时目标值封存（十进制字符串）
 * @param tolerance     检测时允许偏差封存（十进制字符串，要求值）
 * @param readingValue  离群读数封存（十进制字符串，实际值）
 * @param deviation     实际偏差 |读数-目标值|（十进制字符串）
 * @param status        状态：PENDING / ACCEPTED / REJECTED
 * @param detectedAt    检测时间（UTC）
 * @param adjudicatedAt 裁决时间（UTC）；未裁决为 null
 * @param reviews       全部复核记录（按提交顺序）
 */
public record OutlierResponse(
        long id,
        String batchKey,
        long readingId,
        String readingKey,
        int batchRevision,
        String targetValue,
        String tolerance,
        String readingValue,
        String deviation,
        String status,
        Instant detectedAt,
        Instant adjudicatedAt,
        List<ReviewRecordResponse> reviews) {
}