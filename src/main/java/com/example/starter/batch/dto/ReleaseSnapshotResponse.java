package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 放行快照：放行时固化的声明版本、匹配前缀与放行时点（UTC）。
 * 快照创建后不因声明撤回而改写。
 */
public record ReleaseSnapshotResponse(
        String batchKey,
        String declarationKey,
        int version,
        String matchedPrefix,
        Instant releasedAt
) {
}