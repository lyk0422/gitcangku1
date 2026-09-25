package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 来源证明视图。
 *
 * @param revoked true 表示该证明已撤销（记录保留）
 */
public record AttestationResponse(
        String name,
        int version,
        int attestationVersion,
        String repoId,
        String digest,
        int level,
        boolean revoked,
        Instant createdAt) {
}
