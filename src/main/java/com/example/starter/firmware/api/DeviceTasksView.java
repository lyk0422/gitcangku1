package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备端任务清单视图：只含本设备的任务，每条携带筛选时冻结的画像版本。
 */
public record DeviceTasksView(String deviceId, List<LaunchTaskView> tasks) {
}
