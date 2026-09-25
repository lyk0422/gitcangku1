package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。携带任务创建/完成时的发布单版本快照，以及 RELEASE_FROZEN 时固化的冻结令快照。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, Integer releaseVersionAtCreate,
                       Integer releaseVersionAtComplete, FreezeSnapshotView freezeSnapshot,
                       Long emergencyFreezeId) {

    /**
     * 冻结令快照视图，仅当任务状态为 RELEASE_FROZEN 时非空。
     */
    public record FreezeSnapshotView(long freezeId, int freezeVersion, String models, String releaseIds,
                                     String windowStartUtc, String windowEndUtc, String frozenAtUtc) {
        static FreezeSnapshotView of(RolloutTask.FreezeSnapshot s) {
            return new FreezeSnapshotView(s.freezeId(), s.freezeVersion(), s.models(), s.releaseIds(),
                    s.windowStartUtc(), s.windowEndUtc(), s.frozenAtUtc());
        }
    }

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.releaseVersionAtCreate(),
                task.releaseVersionAtComplete(),
                task.freezeSnapshot() == null ? null : FreezeSnapshotView.of(task.freezeSnapshot()),
                task.emergencyFreezeId());
    }
}
