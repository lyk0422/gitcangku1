package com.example.starter.batch.dto;

import com.example.starter.batch.DeclarationStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合格证声明版本响应。validFrom/validTo 为 UTC 区间（左闭右开）；
 * withdrawnAt 未撤回时为 null。
 */
public record DeclarationResponse(
        String declarationKey,
        int version,
        String materialCode,
        List<String> batchNoPrefixes,
        int priority,
        Instant validFrom,
        Instant validTo,
        DeclarationStatus status,
        String actorId,
        Instant publishedAt,
        Instant withdrawnAt
) {
}
