package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)。
 * LINEAR 使用 a/b；HYSTERESIS 使用 slope/upOffset/downOffset，按读数变化方向选择偏移。
 *
 * @param id          证书 ID（自增）
 * @param instrumentId 仪器 ID
 * @param validFrom   有效期起点（UTC，含）
 * @param validTo     有效期终点（UTC，不含）
 * @param certType    证书类型：LINEAR / HYSTERESIS
 * @param a           线性校准系数 a，最多 6 位小数；仅 LINEAR
 * @param b           线性校准偏移 b，最多 6 位小数；仅 LINEAR
 * @param slope       迟滞斜率，最多 6 位小数；仅 HYSTERESIS
 * @param upOffset    迟滞上升方向偏移；仅 HYSTERESIS
 * @param downOffset  迟滞下降方向偏移；仅 HYSTERESIS
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
        BigDecimal slope,
        BigDecimal upOffset,
        BigDecimal downOffset,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {

    /**
     * 是否迟滞证书（按读数方向选择偏移的校准会话专用）。
     */
    public boolean isHysteresis() {
        return certType == CertificateType.HYSTERESIS;
    }
}
