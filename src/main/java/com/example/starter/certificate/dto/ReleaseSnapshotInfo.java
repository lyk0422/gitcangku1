package com.example.starter.certificate.dto;

import java.time.Instant;

/**
 * 放行快照信息：放行时点固化的声明版本、匹配前缀与时点，随后续操作不改写。
 */
public record ReleaseSnapshotInfo(
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        Instant releasedAt
) {
}