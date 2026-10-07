package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ConfigSnapshots;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

import java.util.Map;

/**
 * 投放任务视图。configVersionBefore/configBefore/configAfter 为领取时冻结的配置版本与迁移前后完整快照。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       Integer configVersionBefore, Map<String, Integer> configBefore,
                       Map<String, Integer> configAfter, String failureReason) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.configVersionBefore(),
                ConfigSnapshots.fromJson(task.configBefore()), ConfigSnapshots.fromJson(task.configAfter()),
                task.failureReason());
    }
}
