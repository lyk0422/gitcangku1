package com.example.starter.incident;

import java.time.Instant;

/**
 * 日志封存包头实体，对应 incident_log_seals 表。
 * version 为事件内从 1 开始递增的封存包版本；sealUntil 为封存截至 UTC 时刻
 * （左闭右开区间右端，不含该时刻）。封存后整包不可变，只允许追加更正附录。
 * 时间均为 UTC。
 */
public record IncidentLogSeal(
        long id,
        long incidentId,
        int version,
        Instant sealUntil,
        Instant sealedAt,
        String sealedBy,
        int recordCount,
        int edgeCount,
        Instant createdAt) {
}
