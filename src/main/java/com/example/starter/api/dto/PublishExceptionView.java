package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 发布时使用的豁免双人快照视图，写入后不可变。
 */
public record PublishExceptionView(
        long exceptionId,
        String exceptionKey,
        String vulnerabilityId,
        String reviewerOne,
        String reviewerTwo,
        String reason,
        Instant expiresAt) {
}
