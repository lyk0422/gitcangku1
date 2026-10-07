package com.example.starter.certificate.dto;

import java.time.Instant;

/**
 * 放行快照响应：放行时点固化的声明版本、匹配前缀与时点；此后声明撤回不改写本快照。
 */
public record ReleaseSnapshotResponse(
        String batchKey,
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        Instant releasedAt
) {
}
