package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 豁免视图：状态为 PENDING/CONFIRMED/REVOKED；
 * reviewerTwo 与 confirmedAt 在确认前为 null，revokedAt 在未撤销时为 null。
 */
public record ExceptionResponse(
        String exceptionKey,
        long lockFileId,
        String vulnerabilityId,
        String status,
        String reviewerOne,
        String reviewerTwo,
        String reason,
        Instant expiresAt,
        Instant createdAt,
        Instant confirmedAt,
        Instant revokedAt) {
}
