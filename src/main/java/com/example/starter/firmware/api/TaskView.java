package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。cancelGeneration 为当前取消代次，未请求取消为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, Integer cancelGeneration) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.cancelGeneration());
    }
}
