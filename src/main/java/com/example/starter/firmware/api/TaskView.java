package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。aggregateDigest 为可安装判定时刻固化的聚合摘要，未判定为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, int attemptNo, String status,
                       String fromVersion, String toVersion, String aggregateDigest) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.attemptNo(),
                task.status().name(), order.fromVersion(), order.toVersion(), task.aggregateDigest());
    }
}
