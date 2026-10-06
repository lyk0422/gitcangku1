package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)。
 * LINEAR 使用 a/b；HYSTERESIS 使用 slope/upOffset/downOffset，另一组字段为 null。
 *
 * @param id          证书 ID（自增）
 * @param instrumentId 仪器 ID
 * @param validFrom   有效期起点（UTC，含）
 * @param validTo     有效期终点（UTC，不含）
 * @param certType    证书类型：LINEAR 线性 / HYSTERESIS 迟滞
 * @param a           线性系数 a，最多 6 位小数；HYSTERESIS 为 null
 * @param b           线性偏移 b，最多 6 位小数；HYSTERESIS 为 null
 * @param slope       迟滞斜率，最多 6 位小数；LINEAR 为 null
 * @param upOffset    上升方向偏移，最多 6 位小数；LINEAR 为 null
 * @param downOffset  下降方向偏移，最多 6 位小数；LINEAR 为 null
 * @param revoked     是否已撤销
 * @param revokedAt   撤销时间（UTC），未撤销为 null
 * @param createdAt   创建时间（UTC）
 */
public record Certificate(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        CertType certType,
        BigDecimal a,
        BigDecimal b,
        BigDecimal slope,
        BigDecimal upOffset,
        BigDecimal downOffset,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
