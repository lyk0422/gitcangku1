package com.example.starter.evidence.domain;

import java.time.LocalDateTime;

/**
 * 封条核验历史行，只追加，不可变。
 */
public record SealCheck(
        Long id,
        Long evidenceId,
        String actorId,
        SealCheckResult result,
        String detail,
        LocalDateTime createdAt
) {
}
