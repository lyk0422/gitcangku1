package com.example.starter.certificate.dto;

import java.time.Instant;

/**
 * 合格证声明版本响应。validFrom/validTo 为 UTC instant，区间左闭右开；
 * withdrawnAt/withdrawnBy/withdrawReason 为 null 表示未撤回。
 */
public record DeclarationResponse(
        String declarationKey,
        int version,
        String materialCode,
        String batchNoPrefix,
        int priority,
        Instant validFrom,
        Instant validTo,
        String status,
        String publishedBy,
        Instant createdAt,
        Instant withdrawnAt,
        String withdrawnBy,
        String withdrawReason
) {
}
