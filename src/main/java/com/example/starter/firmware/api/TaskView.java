package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。携带拉取时固化的证书链版本/指纹（未纳管固件为 null）与回执复核状态。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       Integer certChainVersion, String certFingerprint,
                       String reviewStatus, String reviewReason,
                       String reviewedBy, String reviewedAtUtc) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(),
                task.certChainVersion(), task.certFingerprint(),
                task.reviewStatus() == null ? null : task.reviewStatus().name(),
                task.reviewReason(), task.reviewedBy(), task.reviewedAtUtc());
    }
}
