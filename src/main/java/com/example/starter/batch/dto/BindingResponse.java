package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 单条绑定视图。materialCode/batchNoPrefix 为绑定时声明快照；createdAt 为 UTC instant。
 */
public record BindingResponse(
        String batchKey,
        String declarationKey,
        int declarationVersion,
        String materialCode,
        String batchNoPrefix,
        String boundBy,
        Instant createdAt
) {
}
