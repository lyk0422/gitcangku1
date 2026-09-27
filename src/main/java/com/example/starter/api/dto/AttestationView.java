package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 单条见证记录视图（用于明细查询）。
 *
 * @param revoked   true=已撤销（记录保留）
 * @param revokedAt 撤销时间，UTC；未撤销为 null
 */
public record AttestationView(
        String operator,
        String digest,
        boolean revoked,
        Instant attestedAt,
        Instant revokedAt) {
}
