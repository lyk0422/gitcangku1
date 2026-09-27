package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。certChainVersion 为任务创建时固化的证书链版本（无证书要求时为 null）；
 * reviewStatus 为复核状态（NONE/CERTIFICATE_REVOKED_REVIEW/CONFIRMED）。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, String certChainVersion,
                       String reviewStatus) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.certChainVersion(),
                task.reviewStatus().name());
    }
}
