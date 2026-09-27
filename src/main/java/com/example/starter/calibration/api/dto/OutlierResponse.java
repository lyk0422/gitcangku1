package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 离群记录明细响应。同时返回实际偏差（actualDeviation）、允许偏差（allowedDeviation）
 * 与差额（excessDeviation = actualDeviation - allowedDeviation），调用方无需猜测。
 *
 * @param id               离群记录 ID
 * @param batchKey         所属批次键
 * @param batchRevision    检测时的批次修订版本号
 * @param readingId        离群读数 ID
 * @param targetValue      封存目标值（十进制字符串）
 * @param allowedDeviation 封存允许偏差（十进制字符串）
 * @param readingValue     封存读数，校准计算值（十进制字符串）
 * @param actualDeviation  实际偏差 |读数-目标值|（十进制字符串）
 * @param excessDeviation  差额：实际偏差-允许偏差（十进制字符串）
 * @param status           裁决状态：PENDING / ACCEPTED / REJECTED
 * @param detectedAt       离群检测时间（UTC）
 * @param verdictAt        裁决形成时间（UTC）；未裁决时为 null
 * @param verdict          裁决结果；未裁决时为 null
 * @param reviews          全部复核记录（含留痕的失效复核，按提交顺序）
 */
public record OutlierResponse(
        long id,
        String batchKey,
        int batchRevision,
        long readingId,
        String targetValue,
        String allowedDeviation,
        String readingValue,
        String actualDeviation,
        String excessDeviation,
        String status,
        Instant detectedAt,
        Instant verdictAt,
        VerdictResponse verdict,
        List<ReviewResponse> reviews) {
}
