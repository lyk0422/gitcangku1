package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 合格证声明响应。version 为物料内递增版本号；priority 数值越大越优先；
 * allowedBatchNoPrefixes 按发布请求顺序返回（稳定排序）；
 * validFrom/validTo 为 UTC 区间端点，按左闭右开解释；
 * withdrawnBy/withdrawnAt 在 ACTIVE 时为 null，撤回后写入且不再改写。
 */
public record DeclarationResponse(
        String declarationKey,
        String materialCode,
        int version,
        int priority,
        Instant validFrom,
        Instant validTo,
        String status,
        List<String> allowedBatchNoPrefixes,
        String publishedBy,
        String withdrawnBy,
        Instant withdrawnAt,
        Instant publishedAt
) {
}
