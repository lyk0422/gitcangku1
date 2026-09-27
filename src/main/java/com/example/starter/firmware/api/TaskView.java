package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。attemptNo/receivedChunks/requiredChunks 仅对已登记分片清单的发布单任务有值，
 * 旧式发布单任务为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       Integer attemptNo, Integer receivedChunks, Integer requiredChunks) {

    /**
     * 旧式发布单任务视图（无分片清单，完整性字段为 null）。
     */
    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), null, null, null);
    }

    /**
     * 分片清单任务视图：携带当前尝试代次与接收进度（实际值/要求值）。
     */
    public static TaskView of(RolloutTask task, ReleaseOrder order, int attemptNo, int receivedChunks) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), attemptNo, receivedChunks, order.chunkCount());
    }
}
