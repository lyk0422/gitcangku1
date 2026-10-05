package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 校准证书响应。LINEAR 证书 nodes 为 null；CURVE 证书 a/b 为 null，nodes 按原始值递增返回。
 *
 * @param id           证书 ID
 * @param instrumentId 仪器 ID
 * @param validFrom    有效期起点（UTC，含）
 * @param validTo      有效期终点（UTC，不含）
 * @param type         证书类型：LINEAR / CURVE
 * @param a            线性校准系数 a（十进制字符串）；CURVE 为 null
 * @param b            线性校准偏移 b（十进制字符串）；CURVE 为 null
 * @param nodes        曲线节点列表；LINEAR 为 null
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时间（UTC），未撤销为 null
 * @param createdAt    创建时间（UTC）
 */
public record CertificateResponse(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        String type,
        String a,
        String b,
        List<NodeResponse> nodes,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {

    /**
     * 曲线节点响应。
     *
     * @param raw       节点原始值（十进制字符串）
     * @param corrected 节点校正值（十进制字符串）
     */
    public record NodeResponse(String raw, String corrected) {
    }
}
