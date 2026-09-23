package com.example.starter.restitution.repo;

import java.time.LocalDateTime;

/**
 * request_record 幂等记录行。
 */
public record RequestRecordRow(
        String requestId,
        String actorId,
        String opKey,
        String fingerprint,
        int httpStatus,
        String responseBody,
        LocalDateTime createdAt
) {
}
