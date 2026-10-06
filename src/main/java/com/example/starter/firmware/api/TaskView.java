package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。peakBytes 为领取时暂存峰值（历史快照，不随后续维护漂移）；
 * currentAvailableBytes 为设备当前余量（总空间-当前镜像-活跃预留），旧模式两者均为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       Long peakBytes, Long currentAvailableBytes) {

    public static TaskView of(RolloutTask task, ReleaseOrder order, Device device, long reservedBytes) {
        Long available = device.spaceProfileEnabled()
                ? device.totalSpaceBytes() - device.currentImageBytes() - reservedBytes
                : null;
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.peakBytes(), available);
    }
}
