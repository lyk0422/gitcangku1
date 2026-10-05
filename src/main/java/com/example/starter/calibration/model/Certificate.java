package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)。
 *
 * @param id           证书 ID（自增）
 * @param instrumentId 仪器 ID
 * @param certType     证书类型：LINEAR 线性 / CURVE 分段线性曲线
 * @param validFrom    有效期起点（UTC，含）
 * @param validTo      有效期终点（UTC，不含）
 * @param a            校准系数 a，最多 6 位小数；CURVE 类型为 null
 * @param b            校准偏移 b，最多 6 位小数；CURVE 类型为 null
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时间（UTC），未撤销为 null
 * @param createdAt    创建时间（UTC）
 */
public record Certificate(
        long id,
        String instrumentId,
        CertificateType certType,
        Instant validFrom,
        Instant validTo,
        BigDecimal a,
        BigDecimal b,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
