package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量修订版本响应。
 *
 * @param revisionNo  版本号
 * @param targetValue 目标值（十进制字符串）
 * @param tolerance   允许偏差（十进制字符串）
 * @param lowerLimit  允许下限=目标值-偏差（含端点）
 * @param upperLimit  允许上限=目标值+偏差（含端点）
 * @param reason      修订原因；首版为 null
 * @param createdBy   修订创建人
 * @param createdAt   修订创建时间（UTC）
 * @param releasedAt  放行时间（UTC）；未放行为 null
 */
public record RevisionResponse(
        int revisionNo,
        String targetValue,
        String tolerance,
        String lowerLimit,
        String upperLimit,
        String reason,
        String createdBy,
        Instant createdAt,
        Instant releasedAt) {
}
