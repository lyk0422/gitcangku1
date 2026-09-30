package com.example.starter.po;

import java.time.LocalDateTime;

/**
 * 复核幂等记录持久化对象。
 */
public record ReviewRecordPo(
        Long id,
        String requestId,
        String decision,
        String requestHash,
        LocalDateTime reviewedAt) {
}
