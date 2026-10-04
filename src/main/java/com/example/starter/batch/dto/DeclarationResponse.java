package com.example.starter.batch.dto;

import com.example.starter.batch.DeclarationStatus;

import java.time.Instant;

/**
 * 合格证声明版本响应。validFrom/validTo 为 UTC instant，区间左闭右开；
 * 未撤回时 withdrawnBy/withdrawnAt 为 null。
 */
public record DeclarationResponse(
        String declarationKey,
        int version,
        String materialCode,
        String batchNoPrefix,
        int priority,
        Instant validFrom,
        Instant validTo,
        DeclarationStatus status,
        String publishedBy,
        Instant publishedAt,
        String withdrawnBy,
        Instant withdrawnAt
) {
}