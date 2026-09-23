package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 制品版本签名视图。
 */
public record SignatureResponse(
        String name,
        int version,
        String keyId,
        String digest,
        Instant createdAt) {
}
