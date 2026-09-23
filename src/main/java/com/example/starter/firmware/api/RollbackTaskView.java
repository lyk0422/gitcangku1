package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RollbackTask;

/**
 * 回退波次任务视图，expectedVersion/toVersion/sourceReleaseId 为派发时冻结值。
 */
public record RollbackTaskView(long taskId, long planId, int hopIndex, int round, String deviceId,
                               String status, String expectedVersion, String toVersion,
                               long sourceReleaseId, String firstResult) {

    public static RollbackTaskView of(RollbackTask task) {
        return new RollbackTaskView(task.id(), task.planId(), task.hopIndex(), task.round(), task.deviceId(),
                task.status().name(), task.expectedVersion(), task.toVersion(), task.sourceReleaseId(),
                task.firstResult() == null ? null : task.firstResult().name());
    }
}
