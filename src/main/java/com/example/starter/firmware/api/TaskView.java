package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。taskType/challenge/frozenBootCount 仅 BOOT_CHALLENGE 任务非空，
 * challenge 为领取时生成的合成挑战值，frozenBootCount 为领取时冻结的启动计数 b。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, String taskType, String challenge,
                       Long frozenBootCount) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.taskType().name(), task.challenge(),
                task.frozenBootCount());
    }
}
