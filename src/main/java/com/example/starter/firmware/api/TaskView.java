package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。chainVersion 为任务创建时固化的证书链版本（无证书要求时为 null）；
 * reviewStatus 为回执复核标记（无复核为 null）。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, String chainVersion, String reviewStatus) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.chainVersion(),
                task.reviewStatus() == null ? null : task.reviewStatus().name());
    }
}
