package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。增量任务携带拉取时冻结的三个摘要与设备镜像代次；普通任务这些字段为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, String baseDigest, String patchDigest,
                       String targetDigest, Integer imageGeneration) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.baseDigest(), task.patchDigest(),
                task.targetDigest(), task.imageGeneration());
    }
}