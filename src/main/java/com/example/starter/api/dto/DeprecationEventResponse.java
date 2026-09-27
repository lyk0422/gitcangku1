package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 弃用窗口事件历史视图，只追加不回写。
 */
public record DeprecationEventResponse(
        long id,
        long deprecationId,
        String name,
        int version,
        String replacementName,
        int replacementVersion,
        String eventType,
        Instant effectiveAt,
        String note,
        String requestId,
        Instant eventAt) {
}
