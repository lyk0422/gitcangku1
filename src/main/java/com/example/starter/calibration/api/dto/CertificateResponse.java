package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 校准标准器证书响应。
 *
 * @param id                 证书 ID
 * @param standardId         标准器 ID
 * @param instrumentId       仪器 ID（历史兼容字段）
 * @param version            证书版本
 * @param validFrom          有效期起点（UTC，含）
 * @param validTo            有效期终点（UTC，不含；端点时刻即到期）
 * @param a                  补偿系数 a（十进制字符串）
 * @param b                  补偿偏移 b（十进制字符串）
 * @param uncertainty        标准器标准不确定度（十进制字符串）
 * @param uncertaintyVersion 不确定度版本
 * @param singleBatchOnly    是否仅允许单个放行批次引用
 * @param revoked            是否已撤销
 * @param revokedAt          撤销时间（UTC），未撤销为 null
 * @param createdAt          创建时间（UTC）
 */
public record CertificateResponse(
        long id,
        String standardId,
        String instrumentId,
        String version,
        Instant validFrom,
        Instant validTo,
        String a,
        String b,
        String uncertainty,
        String uncertaintyVersion,
        boolean singleBatchOnly,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
