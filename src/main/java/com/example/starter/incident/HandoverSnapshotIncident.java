package com.example.starter.incident;

import java.time.Instant;

/**
 * 联合交接不可变事件快照行，对应 joint_handover_snapshot_incidents 表。
 * 仅在接受成功的同一事务写入；versionAt 为切换时事件 updated_at（UTC 版本）。
 */
public record HandoverSnapshotIncident(
        long id,
        long handoverId,
        long incidentId,
        String incidentKey,
        String commander,
        IncidentStatus status,
        Instant versionAt,
        int ordinal) {
}
