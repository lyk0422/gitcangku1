package com.example.starter.incident;

import java.time.Instant;

/**
 * 命令幂等记录：同一事件内 commandKey 唯一，fingerprint 用于识别同键改参。
 */
public record CommandRecord(
        Long id,
        long incidentId,
        String commandKey,
        String operation,
        String fingerprint,
        int responseStatus,
        String responseBody,
        Instant createdAt) {
}
