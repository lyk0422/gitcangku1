package com.example.starter.restitution.repo;

import java.time.LocalDateTime;

/**
 * claim 行记录。
 */
public record ClaimRow(
        long id,
        long caseId,
        String claimKey,
        String applicant,
        String statement,
        String status,
        long evidenceVersion,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt
) {
}
