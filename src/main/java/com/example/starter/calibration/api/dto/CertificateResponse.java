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
 * @param tempMin      允许环境温度下限（℃，含端点）
 * @param tempMax      允许环境温度上限（℃，含端点）
 * @param humMin       允许环境湿度下限（%RH，含端点）
 * @param humMax       允许环境湿度上限（%RH，含端点）
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
        String tempMin,
        String tempMax,
        String humMin,
        String humMax,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
