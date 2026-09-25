package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 校准证书（标准器证书）响应。
 *
 * @param id                 证书 ID
 * @param instrumentId       仪器 ID
 * @param validFrom          有效期起点（UTC，含）
 * @param validTo            有效期终点（UTC，不含）
 * @param a                  校准系数 a（十进制字符串）
 * @param b                  校准偏移 b（十进制字符串）
 * @param certVersion        证书版本
 * @param compensationCoeff  补偿系数（十进制字符串）
 * @param uncertaintyVersion 不确定度版本
 * @param singleBatchOnly    是否单批次独占
 * @param boundBatchId       已绑定的放行批次 ID，未绑定为 null
 * @param revoked            是否已撤销
 * @param revokedAt          撤销时间（UTC），未撤销为 null
 * @param createdAt          创建时间（UTC）
 */
public record CertificateResponse(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        String a,
        String b,
        String certVersion,
        String compensationCoeff,
        String uncertaintyVersion,
        boolean singleBatchOnly,
        String boundBatchId,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
