package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件实体，对应 incidents 表。
 * commander 为当前指挥人（X-Actor-Id），仅 REPORTED 状态为空；
 * deadlineAt 为遏制期限（UTC）：首次进入 COMMANDING 时按该次接管时刻加等级时限确定，
 * S1=5分钟、S2=15分钟、S3=60分钟、S4=240分钟，交接不重置；REPORTED 状态为空；
 * 被并入事件合并成功时期限作废置空。
 * version 为乐观并发版本号，初始 0，仅在合并成功时双方各加一；
 * mergedIntoIncidentId 仅 MERGED 状态有值，指向存续事件 id，其余为空。
 * 时间均为 UTC 秒级以上的 Instant。
 */
public record Incident(
        long id,
        String incidentKey,
        String severity,
        String summary,
        String reporter,
        IncidentStatus status,
        String commander,
        Instant createdAt,
        Instant updatedAt,
        Instant deadlineAt,
        long version,
        Long mergedIntoIncidentId) {
}
