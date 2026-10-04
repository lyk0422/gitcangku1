package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DualBootTask;
import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 双分区试启动任务视图。trialStartedAtUtc/trialDeadlineUtc 为服务端时钟计算的 UTC 时刻。
 */
public record DualBootTaskView(long taskId, long releaseId, String deviceId, String status,
                               String fromVersion, String toVersion,
                               String targetPartition, String targetVersion,
                               String trialStartedAtUtc, String trialDeadlineUtc) {

    public static DualBootTaskView of(DualBootTask task, ReleaseOrder order) {
        return new DualBootTaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.targetPartition(), task.targetVersion(),
                task.trialStartedAtUtc(), task.trialDeadlineUtc());
    }
}