package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RolloutTask;

/**
 * 设备任务历史中的单次尝试视图：按尝试序号列出前驱、后继与结果。
 * 仅有一次投放的历史数据兼容为 attemptNo=1、predecessorId/successorId 为 null。
 */
public record TaskAttemptView(long taskId, long releaseId, String deviceId, int attemptNo,
                              Long predecessorId, Long successorId, String status, String firstResult) {

    public static TaskAttemptView of(RolloutTask task, Long successorId) {
        return new TaskAttemptView(task.id(), task.releaseId(), task.deviceId(), task.attemptNo(),
                task.predecessorId(), successorId, task.status().name(),
                task.firstResult() == null ? null : task.firstResult().name());
    }
}
