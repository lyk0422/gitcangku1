package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 离群明细视图。目标值、允许偏差与读数为检测时封存的证据。
 *
 * @param id               离群记录 ID
 * @param readingId        触发离群的读数 ID
 * @param readingKey       触发离群的读数键
 * @param batchRevision    检测时的测量修订版本
 * @param targetValue      封存目标值（十进制字符串）
 * @param allowedDeviation 封存允许偏差（十进制字符串）
 * @param readingValue     封存读数值（十进制字符串）
 * @param actualDeviation  实际偏差（十进制字符串）
 * @param excess           超出量 = 实际偏差 - 允许偏差（十进制字符串）
 * @param status           状态：PENDING / ACCEPTED / REJECTED
 * @param decidedAt        裁决时间（UTC）；未裁决为 null
 * @param createdAt        检测时间（UTC）
 * @param reviews          复核历史（按提交顺序）
 */
public record OutlierView(
        long id,
        long readingId,
        String readingKey,
        int batchRevision,
        String targetValue,
        String allowedDeviation,
        String readingValue,
        String actualDeviation,
        String excess,
        String status,
        Instant decidedAt,
        Instant createdAt,
        List<ReviewView> reviews) {
}