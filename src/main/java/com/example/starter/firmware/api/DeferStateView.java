package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskDeferState;

/**
 * 单个任务（同发布单同设备）的顺延累计统计视图。
 */
public record DeferStateView(long releaseId, String deviceId, int deferCount, String lastDeferredAtUtc) {

    public static DeferStateView of(TaskDeferState state) {
        return new DeferStateView(state.releaseId(), state.deviceId(), state.deferCount(),
                state.lastDeferredAtUtc());
    }
}
