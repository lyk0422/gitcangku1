package com.example.starter.incident;

import java.time.Instant;

/**
 * 联合交接不可变 OPEN 任务快照行，对应 joint_handover_snapshot_tasks 表。
 * 仅冻结时仍 OPEN 的任务入快照；blockerKeys 为阻塞事件键有序 JSON 数组字符串；
 * versionAt 为切换时任务 updated_at（UTC 版本）。
 */
public record HandoverSnapshotTask(
        long id,
        long handoverId,
        long incidentId,
        long taskId,
        String taskKey,
        TaskStatus status,
        Instant versionAt,
        String blockerKeys,
        int ordinal) {
}
