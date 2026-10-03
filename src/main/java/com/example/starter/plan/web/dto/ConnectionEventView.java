package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续历史事件视图（只追加不改写）。可空字段以 null 返回，语义见字段注释。
 */
public record ConnectionEventView(
        long id,
        long connectionId,
        String eventType,
        String predecessorKey,
        String successorKey,
        int predecessorVersion,
        Instant oldTimeUtc,
        Instant newTimeUtc,
        Integer requiredMinutes,
        Integer actualMinutes,
        Integer deficitMinutes,
        Instant windowStartUtc,
        Instant windowEndUtc,
        Instant createdAt) {
}