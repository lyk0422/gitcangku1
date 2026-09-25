package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 异常段逐段处置响应；处置一旦提交不可改写。
 */
public record DispositionResponse(
        String segmentKey,
        String actionNote,
        String actorId,
        Instant createdAt
) {
}
