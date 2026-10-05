package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。双分区任务额外携带发布类型、尝试序号与试启动字段（未进入 TRIAL 时为 null）。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, String kind, int attemptNo,
                       String targetPartition, String targetVersion, String trialStartedAtUtc) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), order.kind().name(), task.attemptNo(),
                task.targetPartition(), task.targetVersion(), task.trialStartedAtUtc());
    }
}