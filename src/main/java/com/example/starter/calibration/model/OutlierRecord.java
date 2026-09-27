package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离群记录。检测时封存目标值、允许偏差、读数值、实际偏差、修订版本、证书版本与提交人；
 * 封存字段不可变，仅 status/verdict/resolvedAt 在裁决时一次性流转。
 *
 * @param id               离群记录 ID（自增）
 * @param batchId          所属批次 ID
 * @param readingId        离群读数 ID（唯一）
 * @param batchRevision    检测时的批次测量修订版本
 * @param certificateId    检测时绑定的证书 ID（证书版本）
 * @param targetValue      封存的目标值
 * @param allowedDeviation 封存的允许偏差（含端点）
 * @param readingValue     封存的读数值
 * @param deviation        实际偏差 |读数-目标值|
 * @param submittedBy      读数提交人（复核人须不同于该角色）
 * @param status           状态：PENDING 待裁决 / ACCEPTED 已接受 / REJECTED 已拒绝
 * @param verdict          有效裁决：ACCEPT/REJECT；未裁决为 null
 * @param resolvedAt       裁决时间（UTC）；未裁决为 null
 * @param createdAt        检测时间（UTC）
 */
public record OutlierRecord(
        long id,
        long batchId,
        long readingId,
        int batchRevision,
        long certificateId,
        BigDecimal targetValue,
        BigDecimal allowedDeviation,
        BigDecimal readingValue,
        BigDecimal deviation,
        String submittedBy,
        OutlierStatus status,
        ReviewDecision verdict,
        Instant resolvedAt,
        Instant createdAt) {
}
