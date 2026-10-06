package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 校准证书响应。LINEAR 输出 a/b；HYSTERESIS 输出 slope/upOffset/downOffset，另一组为 null。
 *
 * @param id           证书 ID
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（UTC，含）
 * @param validTo      有效期终点（UTC，不含）
 * @param certType     证书类型：LINEAR / HYSTERESIS
 * @param a            线性系数 a（十进制字符串），HYSTERESIS 为 null
 * @param b            线性偏移 b（十进制字符串），HYSTERESIS 为 null
 * @param slope        迟滞斜率（十进制字符串），LINEAR 为 null
 * @param upOffset     上升方向偏移（十进制字符串），LINEAR 为 null
 * @param downOffset   下降方向偏移（十进制字符串），LINEAR 为 null
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时间（UTC），未撤销为 null
 * @param createdAt    创建时间（UTC）
 */
public record CertificateResponse(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        String certType,
        String a,
        String b,
        String slope,
        String upOffset,
        String downOffset,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
