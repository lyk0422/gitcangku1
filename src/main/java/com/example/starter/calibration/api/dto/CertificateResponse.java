package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 校准证书响应。
 *
 * @param id           证书 ID
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（UTC，含）
 * @param validTo      有效期终点（UTC，不含）
 * @param a            校准系数 a（十进制字符串）
 * @param b            校准偏移 b（十进制字符串）
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时间（UTC），未撤销为 null
 * @param createdAt    创建时间（UTC）
 */
public record CertificateResponse(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        String a,
        String b,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
