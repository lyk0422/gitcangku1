package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 参考漂移档案响应。
 *
 * @param id              漂移档案 ID
 * @param instrumentId    仪器 ID
 * @param profiledAt      参考测量时刻（UTC，毫秒精度）
 * @param referenceOffset 参考偏移（十进制字符串）
 * @param revoked         是否已撤销
 * @param revokedAt       撤销时间（UTC），未撤销为 null
 * @param createdAt       创建时间（UTC）
 */
public record DriftProfileResponse(
        long id,
        String instrumentId,
        Instant profiledAt,
        String referenceOffset,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
