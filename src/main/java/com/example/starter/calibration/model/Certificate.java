package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)。
 * LINEAR 证书携带系数 a 与偏移 b；CURVE 证书的 a/b 为 null，节点另存于 certificate_node。
 *
 * @param id          证书 ID（自增）
 * @param instrumentId 仪器 ID
 * @param validFrom   有效期起点（UTC，含）
 * @param validTo     有效期终点（UTC，不含）
 * @param certType    证书类型：LINEAR / CURVE
 * @param a           线性校准系数 a，最多 6 位小数；CURVE 为 null
 * @param b           线性校准偏移 b，最多 6 位小数；CURVE 为 null
 * @param revoked     是否已撤销
 * @param revokedAt   撤销时间（UTC），未撤销为 null
 * @param createdAt   创建时间（UTC）
 */
public record Certificate(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        CertificateType certType,
        BigDecimal a,
        BigDecimal b,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
