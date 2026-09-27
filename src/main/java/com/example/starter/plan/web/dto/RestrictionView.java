package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 限速令视图。revokedAt 为 null 表示未撤销；operator 为 null 表示登记时未提供。
 */
public record RestrictionView(
        long id,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        int maxSpeedKmh,
        String status,
        int version,
        String operator,
        Instant createdAt,
        Instant revokedAt) {
}
