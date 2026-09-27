package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 单条见证记录视图（含已撤销的历史见证）。
 *
 * @param revoked   是否已撤销
 * @param createdAt 见证首次提交时间，UTC
 * @param revokedAt 撤销时间，UTC；未撤销为 null
 */
public record AttestationView(
        String role,
        String checksum,
        boolean revoked,
        Instant createdAt,
        Instant revokedAt) {
}
