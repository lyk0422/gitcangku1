package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。frozenChainId/frozenChainVersion 为任务创建时刻固化的证书链快照（未启用证书时为 null）。
 * reviewStatus 为撤销链回执的人工复核状态：CERTIFICATE_REVOKED_REVIEW 待确认、CONFIRMED 已确认、
 * REJECTED 已拒绝；无复核单时为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       Long frozenChainId, Integer frozenChainVersion, String reviewStatus) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.frozenChainId(), null, null);
    }
}
