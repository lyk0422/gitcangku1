package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)。
 * 温湿度范围为放行时允许的环境区间，四个端点均包含在范围内。
 *
 * @param id          证书 ID（自增）
 * @param instrumentId 仪器 ID
 * @param validFrom   有效期起点（UTC，含）
 * @param validTo     有效期终点（UTC，不含）
 * @param a           校准系数 a，最多 6 位小数
 * @param b           校准偏移 b，最多 6 位小数
 * @param tempMin     允许环境温度下限（℃，含端点）
 * @param tempMax     允许环境温度上限（℃，含端点）
 * @param humMin      允许环境湿度下限（%RH，含端点）
 * @param humMax      允许环境湿度上限（%RH，含端点）
 * @param revoked     是否已撤销
 * @param revokedAt   撤销时间（UTC），未撤销为 null
 * @param createdAt   创建时间（UTC）
 */
public record Certificate(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        BigDecimal a,
        BigDecimal b,
        BigDecimal tempMin,
        BigDecimal tempMax,
        BigDecimal humMin,
        BigDecimal humMax,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
