package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 产率登记/修订响应：整单在单事务内落定，entries 为各批次最终记录视图。
 * submittedAt 为 UTC instant。
 */
public record SubmitYieldResponse(
        String yieldKey,
        List<YieldEntryResponse> entries,
        Instant submittedAt
) {
}
