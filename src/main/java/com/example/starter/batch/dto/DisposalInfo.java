package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 隔离处置信息（只读）：托盘未处置时为 null。
 */
public record DisposalInfo(
        String reason,
        Instant createdAt
) {
}
