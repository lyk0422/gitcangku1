package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。
 *
 * @param taskId     任务ID（每次尝试独立）
 * @param releaseId  所属发布单ID
 * @param deviceId   设备ID
 * @param status     任务状态
 * @param fromVersion 来源固件版本
 * @param toVersion   目标固件版本
 * @param attemptNo  尝试序号，从1开始，原任务为第1次
 * @param prevTaskId 前驱任务ID，首次尝试为 null
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, int attemptNo, Long prevTaskId) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.attemptNo(), task.prevTaskId());
    }
}
