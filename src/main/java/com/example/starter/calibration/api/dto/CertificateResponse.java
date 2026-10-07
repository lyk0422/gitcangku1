package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 校准证书响应。二次模式输出 c 与输入闭区间 [domainLo, domainHi]，线性模式对应字段为 null。
 *
 * @param id           证书 ID
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（UTC，含）
 * @param validTo      有效期终点（UTC，不含）
 * @param mode         证书模式：LINEAR / QUADRATIC
 * @param a            校准系数 a（十进制字符串）
 * @param b            校准偏移 b（十进制字符串）
 * @param c            二次常数项 c（十进制字符串），线性模式为 null
 * @param domainLo     二次输入闭区间下界 L（十进制字符串），线性模式为 null
 * @param domainHi     二次输入闭区间上界 U（十进制字符串），线性模式为 null
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时间（UTC），未撤销为 null
 * @param createdAt    创建时间（UTC）
 */
public record CertificateResponse(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        String mode,
        String a,
        String b,
        String c,
        String domainLo,
        String domainHi,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
