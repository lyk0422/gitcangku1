package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)。
 * LINEAR 模式：y = a×读数 + b；QUADRATIC 模式：y = a×x² + b×x + c，
 * 输入闭区间 [domainLo, domainHi]，导数在区间内严格同号（创建时由端点精确验证）。
 *
 * @param id          证书 ID（自增）
 * @param instrumentId 仪器 ID
 * @param validFrom   有效期起点（UTC，含）
 * @param validTo     有效期终点（UTC，不含）
 * @param mode        证书模式：LINEAR / QUADRATIC
 * @param a           校准系数 a，最多 6 位小数；二次模式非零
 * @param b           校准偏移 b，最多 6 位小数
 * @param c           二次常数项 c，最多 6 位小数；线性模式为 null
 * @param domainLo    二次输入闭区间下界 L（含）；线性模式为 null
 * @param domainHi    二次输入闭区间上界 U（含）；线性模式为 null
 * @param revoked     是否已撤销
 * @param revokedAt   撤销时间（UTC），未撤销为 null
 * @param createdAt   创建时间（UTC）
 */
public record Certificate(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        CertificateMode mode,
        BigDecimal a,
        BigDecimal b,
        BigDecimal c,
        BigDecimal domainLo,
        BigDecimal domainHi,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
