package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 限速令视图。createdAt/updatedAt/revokedAt 为 UTC 毫秒时间戳；
 * revokedAt 为 null 表示未撤销。
 */
public record RestrictionResponse(
        String restrictionKey,
        int version,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        int maxSpeedKmh,
        String status,
        String operator,
        long createdAt,
        long updatedAt,
        Long revokedAt) {
}