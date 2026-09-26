package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status, int attempt,
                       String fromVersion, String toVersion) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                task.attempt(), order.fromVersion(), order.toVersion());
    }
}
