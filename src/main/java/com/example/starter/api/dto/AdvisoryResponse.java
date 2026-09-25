package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 漏洞公告视图：公告坐标、严重级别与 UTC 到期时刻。
 */
public record AdvisoryResponse(
        long id,
        String vulnerabilityId,
        String artifactName,
        int artifactVersion,
        String severity,
        Instant expiresAt,
        Instant updatedAt) {
}
