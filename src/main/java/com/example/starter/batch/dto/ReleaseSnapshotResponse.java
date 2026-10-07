package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 放行快照视图：放行判定时点固化的声明版本、匹配前缀与时点；不随声明撤回改写。
 */
public record ReleaseSnapshotResponse(
        String batchKey,
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        Instant releasedAt
) {
}
