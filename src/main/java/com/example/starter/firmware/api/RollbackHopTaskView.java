package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RollbackHopTask;

/**
 * 回跳任务视图：携带冻结版本快照与回执凭证。
 */
public record RollbackHopTaskView(long hopTaskId, long planId, String deviceId, int hopIndex,
                                  int roundNo, String expectedVersion, String targetVersion,
                                  long sourceReleaseId, String status, String receiptKey) {

    public static RollbackHopTaskView of(RollbackHopTask task) {
        return new RollbackHopTaskView(task.id(), task.planId(), task.deviceId(), task.hopIndex(),
                task.roundNo(), task.expectedVersion(), task.targetVersion(), task.sourceReleaseId(),
                task.status().name(), task.receiptKey());
    }
}
