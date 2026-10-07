package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 参考漂移档案响应。
 *
 * @param id           档案 ID
 * @param instrumentId 仪器 ID
 * @param referencedAt 参考测量时刻（UTC 毫秒）
 * @param offset       参考偏移（十进制字符串）
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时间（UTC），未撤销为 null
 * @param createdAt    创建时间（UTC）
 */
public record DriftArchiveResponse(
        long id,
        String instrumentId,
        Instant referencedAt,
        String offset,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
