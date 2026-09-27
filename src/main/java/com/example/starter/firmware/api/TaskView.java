package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。
 *
 * @param attempt          当前接收尝试代次，从1开始
 * @param integrityReason  最近一次完整性失败原因代码；无则为 null
 * @param installableAtUtc 最近一次判定可安装的 UTC 时刻；从未可安装为 null
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status, int attempt,
                       String fromVersion, String toVersion, String integrityReason,
                       String installableAtUtc) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(), task.attempt(),
                order.fromVersion(), order.toVersion(), task.integrityReason(), task.installableAtUtc());
    }
}
