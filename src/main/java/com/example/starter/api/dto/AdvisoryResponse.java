package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 漏洞公告视图；expiresAt 为 null 表示永久有效。
 */
public record AdvisoryResponse(
        String vulnerabilityId,
        String artifactName,
        int artifactVersion,
        String severity,
        Instant expiresAt,
        Instant updatedAt) {
}
