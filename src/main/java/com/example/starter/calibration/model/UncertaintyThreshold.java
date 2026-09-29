package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器不确定度阈值版本。版本一经创建不改写，阈值修订只追加新版本；
 * 某时刻生效版本为 valid_from &lt;= 时刻的最新未停用版本（区间左闭右开）。
 *
 * @param id             阈值版本行 ID（自增）
 * @param instrumentId   仪器 ID
 * @param version        阈值版本号，同一仪器内从 1 递增
 * @param thresholdValue 合成不确定度允许上限（非负，含端点比较），最多 6 位小数
 * @param validFrom      生效起点（UTC，左闭）
 * @param revoked        该版本是否已停用；停用后时刻匹配回退到更早版本
 * @param revokedAt      停用时间（UTC），未停用为 null
 * @param createdAt      版本创建时间（UTC）
 */
public record UncertaintyThreshold(
        long id,
        String instrumentId,
        int version,
        BigDecimal thresholdValue,
        Instant validFrom,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
