package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件实体，对应 incidents 表。
 * commander 为当前指挥人（X-Actor-Id），仅 REPORTED 状态为空；
 * deadlineAt 为遏制期限（UTC）：首次进入 COMMANDING 时按该次接管时刻加等级时限确定，
 * S1=5分钟、S2=15分钟、S3=60分钟、S4=240分钟，交接不重置；REPORTED 状态为空。
 * blockedFrom 为进入 EXTERNAL_BLOCKED 前的状态，仅 EXTERNAL_BLOCKED 有值，
 * 替换机构配置恢复原状态后清空。
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
        IncidentStatus blockedFrom) {
}
