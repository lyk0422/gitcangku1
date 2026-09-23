package com.example.starter.restitution.repo;

import java.time.LocalDateTime;

/**
 * approval 行记录。
 */
public record ApprovalRow(
        long id,
        long caseId,
        long claimId,
        String reviewer,
        long evidenceVersion,
        LocalDateTime createdAt
) {
}
