package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 效价模型修订响应：返回修订后的最新版本视图与修订固化时刻。
 */
public record PotencyRevisionResponse(
        String batchKey,
        PotencyView potency,
        Instant revisedAt
) {
}
