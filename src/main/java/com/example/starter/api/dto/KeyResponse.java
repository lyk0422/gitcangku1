package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 签名钥匙状态视图。
 *
 * @param revoked   是否已撤销
 * @param revokedAt 撤销时间（UTC）；未撤销为 null
 */
public record KeyResponse(
        String keyId,
        boolean revoked,
        Instant revokedAt) {
}
