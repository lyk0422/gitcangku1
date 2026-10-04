package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 批次合格证绑定响应。matchedPrefix 为绑定时匹配成功的批号前缀快照；boundAt 为 UTC instant。
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