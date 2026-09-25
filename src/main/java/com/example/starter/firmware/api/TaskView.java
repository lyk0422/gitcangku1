package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。fromVersion/toVersion 为任务创建时固化的发布快照；
 * receiptFromVersion/receiptToVersion 为首次回执完成时固化的发布快照，未回执为 null；
 * freezeOrderId/freezeSnapshot 为冻结中的冻结令及其快照，未冻结为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       String receiptFromVersion, String receiptToVersion,
                       Long freezeOrderId, String freezeSnapshot) {

    public static TaskView of(RolloutTask task) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                task.fromVersion(), task.toVersion(), task.receiptFromVersion(), task.receiptToVersion(),
                task.freezeOrderId(), task.freezeSnapshot());
    }
}
