package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。moduleDigest、matrixVersion、exemptionVersion 为创建任务时固化的快照，
 * 后续设备上报变化、矩阵修订或豁免修改不改写历史任务。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       String moduleDigest, Integer matrixVersion, Integer exemptionVersion) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.moduleDigest(),
                task.matrixVersion(), task.exemptionVersion());
    }
}
