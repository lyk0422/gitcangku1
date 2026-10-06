package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。peakBytes 为领取时峰值字节数（当时旧镜像+包+新镜像），
 * 历史快照不随设备当前余量漂移；旧模式任务的空间字段为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       Long reservedBytes, Long peakBytes) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.reservedBytes(), task.peakBytes());
    }
}
