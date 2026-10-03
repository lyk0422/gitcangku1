package com.example.starter.certificate.dto;

import java.time.Instant;
import java.util.List;

/**
 * 声明版本明细响应。validFrom/validTo 为 UTC instant，区间左闭右开；
 * withdrawnAt/withdrawnBy 未撤回时为 null。
 */
public record DeclarationVersionResponse(
        String declarationKey,
        int version,
        String supplierId,
        String materialCode,
        List<String> prefixes,
        int priority,
        Instant validFrom,
        Instant validTo,
        String status,
        Instant createdAt,
        Instant withdrawnAt,
        String withdrawnBy
) {
}