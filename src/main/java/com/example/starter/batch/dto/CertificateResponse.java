package com.example.starter.batch.dto;

import com.example.starter.batch.DeclarationStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合格证声明版本明细。validFrom/validTo 为 UTC instant，区间左闭右开；
 * withdrawnBy/withdrawnAt 在未撤回（ACTIVE）时为 null，撤回后不再变化。
 */
public record CertificateResponse(
        String declarationKey,
        int version,
        String materialCode,
        List<PrefixView> prefixes,
        Instant validFrom,
        Instant validTo,
        DeclarationStatus status,
        String publishedBy,
        Instant publishedAt,
        String withdrawnBy,
        Instant withdrawnAt
) {

    /**
     * 声明前缀视图：按 priority 降序、发布顺序升序稳定排序。
     */
    public record PrefixView(
            String prefix,
            int priority
    ) {
    }
}