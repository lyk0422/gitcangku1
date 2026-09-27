package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 弃用窗口当前状态视图。
 *
 * @param status     REGISTERED=登记中（含已生效），REVOKED=已撤销
 * @param revokedAt  撤销 UTC 时刻；未撤销为 null（null 语义稳定，历史不改写）
 */
public record DeprecationWindowView(
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt,
        String note,
        String status,
        Instant createdAt,
        Instant revokedAt) {
}
