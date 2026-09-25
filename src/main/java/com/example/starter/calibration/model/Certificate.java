package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书（标准器证书）。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)，
 * 端点到期即无效。证书记录证书版本、补偿系数与不确定度版本；
 * singleBatchOnly 证书在首次被放行批次引用时绑定该批次。
 *
 * @param id                 证书 ID（自增）
 * @param instrumentId       仪器 ID
 * @param validFrom          有效期起点（UTC，含）
 * @param validTo            有效期终点（UTC，不含）
 * @param a                  校准系数 a，最多 6 位小数
 * @param b                  校准偏移 b，最多 6 位小数
 * @param certVersion        证书版本
 * @param compensationCoeff  补偿系数，最多 6 位小数
 * @param uncertaintyVersion 不确定度版本
 * @param singleBatchOnly    是否单批次独占
 * @param boundBatchId       已绑定的放行批次 ID，未绑定为 null
 * @param revoked            是否已撤销
 * @param revokedAt          撤销时间（UTC），未撤销为 null
 * @param createdAt          创建时间（UTC）
 */
public record Certificate(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        BigDecimal a,
        BigDecimal b,
        String certVersion,
        BigDecimal compensationCoeff,
        String uncertaintyVersion,
        boolean singleBatchOnly,
        String boundBatchId,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
