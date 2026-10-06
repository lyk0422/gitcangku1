package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.BootTask;
import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 双分区试启动任务视图。
 */
public record BootTaskView(long taskId, long releaseId, String deviceId, int attemptNo, String status,
                           String targetPartition, String targetVersion, String trialStartedAtUtc,
                           String fromVersion, String toVersion) {

    public static BootTaskView of(BootTask task, ReleaseOrder order) {
        return new BootTaskView(task.id(), task.releaseId(), task.deviceId(), task.attemptNo(),
                task.status().name(), task.targetPartition(), task.targetVersion(),
                task.trialStartedAtUtc(), order.fromVersion(), order.toVersion());
    }
}
