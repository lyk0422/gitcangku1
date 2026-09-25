package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件实体，对应 incidents 表。
 * domain 为隔离域（REAL/DRILL）；drillBatchKey 仅演练域有值，表示所属演练批次。
 * commander 为当前指挥人（X-Actor-Id），仅 REPORTED 状态为空；
 * 时间均为 UTC 秒级以上的 Instant。
 */
public record Incident(
        long id,
        String incidentKey,
        Domain domain,
        String drillBatchKey,
        String severity,
        String summary,
        String reporter,
        IncidentStatus status,
        String commander,
        Instant createdAt,
        Instant updatedAt) {
}
