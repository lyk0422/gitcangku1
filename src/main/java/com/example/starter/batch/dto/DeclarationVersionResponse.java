package com.example.starter.batch.dto;

import com.example.starter.batch.DeclarationStatus;

import java.time.Instant;

/**
 * 声明版本视图。validFrom/validTo/createdAt 为 UTC instant；withdrawnAt 未撤回时为 null。
 */
public record DeclarationVersionResponse(
        String declarationKey,
        int version,
        String materialCode,
        String batchNoPrefix,
        int priority,
        Instant validFrom,
        Instant validTo,
        DeclarationStatus status,
        String operator,
        Instant createdAt,
        Instant withdrawnAt
) {
}
