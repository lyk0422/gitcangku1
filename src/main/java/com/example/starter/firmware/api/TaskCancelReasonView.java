package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskCancelReason;

/**
 * 任务不可变取消原因视图。
 */
public record TaskCancelReasonView(long id, long taskId, long releaseId, String deviceId,
                                  String reasonCode, String detail, String operator, String createdAt) {

    public static TaskCancelReasonView of(TaskCancelReason reason) {
        return new TaskCancelReasonView(reason.id(), reason.taskId(), reason.releaseId(), reason.deviceId(),
                reason.reasonCode(), reason.detail(), reason.operator(), reason.createdAt());
    }
}
