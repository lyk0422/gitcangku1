package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 校准证书响应。温湿度边界为 null 表示该证书未定义环境允许范围。
 *
 * @param id           证书 ID
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（UTC，含）
 * @param validTo      有效期终点（UTC，不含）
 * @param a            校准系数 a（十进制字符串）
 * @param b            校准偏移 b（十进制字符串）
 * @param tempMin      允许温度下限（℃，含端点）；null 未定义
 * @param tempMax      允许温度上限（℃，含端点）；null 未定义
 * @param humidityMin  允许湿度下限（%RH，含端点）；null 未定义
 * @param humidityMax  允许湿度上限（%RH，含端点）；null 未定义
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
        String humidityMin,
        String humidityMax,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
