package com.example.starter.site.dto;

import java.time.Instant;

/**
 * 隔离记录视图。removedAt 为 null 表示尚未拆除。
 */
public record IsolationView(
        String isolationKey,
        String deviceId,
        Instant plannedStartUtc,
        Instant plannedEndUtc,
        String lockedBy,
        String status,
        Instant createdAt,
        Instant removedAt) {
}
