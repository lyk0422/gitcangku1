package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 参考漂移档案：同仪器 UTC 毫秒时刻及参考偏移。创建后不可修改，仅可撤销。
 *
 * @param id              漂移档案 ID（自增）
 * @param instrumentId    仪器 ID
 * @param profiledAt      参考测量时刻（UTC，毫秒精度）
 * @param referenceOffset 参考偏移，最多 6 位小数
 * @param revoked         是否已撤销
 * @param revokedAt       撤销时间（UTC），未撤销为 null
 * @param createdAt       创建时间（UTC）
 */
public record DriftProfile(
        long id,
        String instrumentId,
        Instant profiledAt,
        BigDecimal referenceOffset,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
