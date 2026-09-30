package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ProfileTask;

/**
 * 画像投放任务视图，携带筛选时冻结的画像版本，供失败率审计。
 */
public record LaunchTaskView(long taskId, long launchId, String deviceId, int profileVersion,
                             String status) {

    public static LaunchTaskView of(ProfileTask task) {
        return new LaunchTaskView(task.id(), task.launchId(), task.deviceId(),
                task.profileVersion(), task.status().name());
    }
}
