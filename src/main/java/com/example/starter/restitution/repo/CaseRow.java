package com.example.starter.restitution.repo;

import java.time.LocalDateTime;

/**
 * restitution_case 行记录。
 */
public record CaseRow(
        long id,
        String caseKey,
        String status,
        long version,
        LocalDateTime decidedAt,
        LocalDateTime createdAt
) {
}
