package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DualTask;

/**
 * 双分区试启动任务视图。每阶段响应经幂等快照冻结：重放旧试启动键返回当时阶段而非后来终态。
 */
public record DualTaskView(long taskId, long releaseId, String deviceId, String status,
                           String targetPartition, String targetVersion, String trialStartedAtUtc,
                           String settledAtUtc, String rollbackReason) {

    public static DualTaskView of(DualTask task) {
        return new DualTaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                task.targetPartition(), task.targetVersion(), task.trialStartedAtUtc(),
                task.settledAtUtc(), task.rollbackReason() == null ? null : task.rollbackReason().name());
    }
}
