package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。taskType 区分普通回执任务与安装握手任务；
 * cancelGeneration 为当前取消代次（0 表示未请求取消），供设备停止确认时回传。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String taskType, int cancelGeneration,
                       String fromVersion, String toVersion) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                task.taskType().name(), task.cancelGeneration(),
                order.fromVersion(), order.toVersion());
    }
}
