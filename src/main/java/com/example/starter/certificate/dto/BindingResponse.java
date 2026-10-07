package com.example.starter.certificate.dto;

import java.time.Instant;

/**
 * 批次合格证绑定响应。boundAt 为 UTC instant。
 */
public record BindingResponse(
        String batchKey,
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        String boundBy,
        Instant boundAt
) {
}
