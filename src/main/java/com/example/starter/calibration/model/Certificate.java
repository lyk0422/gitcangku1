package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)。
 * 温湿度四个边界为环境补偿放行的允许范围（均含端点）；为 null 表示该证书未定义环境范围，
 * 环境补偿放行会被拒绝。
 *
 * @param id           证书 ID（自增）
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（UTC，含）
 * @param validTo      有效期终点（UTC，不含）
 * @param a            校准系数 a，最多 6 位小数
 * @param b            校准偏移 b，最多 6 位小数
 * @param tempMin      允许温度下限（℃，含端点）；null 未定义
 * @param tempMax      允许温度上限（℃，含端点）；null 未定义
 * @param humidityMin  允许湿度下限（%RH，含端点）；null 未定义
 * @param humidityMax  允许湿度上限（%RH，含端点）；null 未定义
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时间（UTC），未撤销为 null
 * @param createdAt    创建时间（UTC）
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
        BigDecimal humidityMin,
        BigDecimal humidityMax,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {

    /**
     * 是否定义了完整的环境允许范围：四个边界都非空才允许环境补偿放行。
     */
    public boolean hasEnvironmentRange() {
        return tempMin != null && tempMax != null && humidityMin != null && humidityMax != null;
    }
}
