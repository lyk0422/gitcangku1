package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。targetSecurityIndex 为任务创建时冻结的目标安全索引；
 * receiptSecurityIndex 为首次回执携带的证明索引（安装证据）。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, Integer targetSecurityIndex,
                       Integer receiptSecurityIndex) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.targetSecurityIndex(),
                task.receiptSecurityIndex());
    }
}
