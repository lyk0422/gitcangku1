package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 离群记录明细。deviation 为实际偏差，tolerance 为允许偏差（要求值），
 * excessDeviation 为超出量（差额 = deviation - tolerance）。
 *
 * @param id              离群记录 ID
 * @param batchKey        所属批次键
 * @param readingId       离群读数 ID
 * @param readingKey      离群读数业务键
 * @param targetValue     封存目标值（十进制字符串）
 * @param tolerance       封存允许偏差（十进制字符串）
 * @param readingValue    封存原始读数（十进制字符串）
 * @param computedValue   封存计算值（十进制字符串）
 * @param deviation       实际偏差（十进制字符串）
 * @param excessDeviation 超出允许偏差的差额（十进制字符串）
 * @param status          状态：PENDING / ACCEPTED / REJECTED
 * @param reviewsReceived 当前有效复核人数（实际值）
 * @param reviewsRequired 裁决所需人数（要求值，固定为 2）
 * @param verdictAt       裁决时间（UTC）；未裁决为 null
 * @param createdAt       检测时间（UTC）
 * @param reviews         复核明细（含 STALE，按提交顺序）
 */
public record OutlierResponse(
        long id,
        String batchKey,
        long readingId,
        String readingKey,
        String targetValue,
        String tolerance,
        String readingValue,
        String computedValue,
        String deviation,
        String excessDeviation,
        String status,
        int reviewsReceived,
        int reviewsRequired,
        Instant verdictAt,
        Instant createdAt,
        List<ReviewItem> reviews) {
}