package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 来源证明视图。
 *
 * @param revoked 是否已撤销：撤销后未发布锁定图不可使用，已发布快照不倒改
 */
public record AttestationResponse(
        long id,
        String name,
        int version,
        String sourceRepository,
        String buildDigest,
        int attestationLevel,
        String operator,
        boolean revoked,
        Instant createdAt) {
}
