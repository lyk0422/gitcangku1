package com.example.starter.incident;

import java.time.Instant;

/**
 * 联合交接不可变未确认升级快照行，对应 joint_handover_snapshot_escalations 表。
 * 仅冻结时仍 OPEN（未确认）的升级记录入快照；versionAt 为升级记录 updated_at（UTC 版本）。
 */
public record HandoverSnapshotEscalation(
        long id,
        long handoverId,
        long incidentId,
        long escalationId,
        Instant versionAt,
        int ordinal) {
}
