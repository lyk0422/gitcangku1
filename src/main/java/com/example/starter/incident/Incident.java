package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件实体，对应 incidents 表。
 * domain 为所属域（REAL 真实 / DRILL 演练）；drillKey/drillBatch 仅 DRILL 域非空。
 * commander 为当前指挥人（X-Actor-Id），仅 REPORTED 状态为空；
 * 时间均为 UTC 秒级以上的 Instant。
 */
public record Incident(
        long id,
        Domain domain,
        String incidentKey,
        String drillKey,
        String drillBatch,
        String severity,
        String summary,
        String reporter,
        IncidentStatus status,
        String commander,
        Instant createdAt,
        Instant updatedAt) {
}
