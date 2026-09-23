package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 可信钥匙视图，revoked=true 时 revokedAt 非空。
 */
public record KeyResponse(
        String keyId,
        boolean revoked,
        Instant createdAt,
        Instant revokedAt) {
}
