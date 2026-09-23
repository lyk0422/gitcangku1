package com.example.starter.restitution.repo;

import java.time.LocalDateTime;

/**
 * evidence 行记录。
 */
public record EvidenceRow(
        long id,
        long caseId,
        long claimId,
        String evidenceKey,
        String summary,
        String status,
        LocalDateTime createdAt,
        LocalDateTime revokedAt
) {
}
